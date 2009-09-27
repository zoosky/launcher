/* sbt -- Simple Build Tool
 * Copyright 2009 Mark Harrah
 */
package xsbt.boot

import java.io.{File, FileWriter, PrintWriter, Writer}

import org.apache.ivy.{core, plugins, util, Ivy}
import core.LogOptions
import core.cache.DefaultRepositoryCacheManager
import core.event.EventManager
import core.module.id.ModuleRevisionId
import core.module.descriptor.{Configuration => IvyConfiguration, DefaultDependencyDescriptor, DefaultModuleDescriptor, ModuleDescriptor}
import core.report.ResolveReport
import core.resolve.{ResolveEngine, ResolveOptions}
import core.retrieve.{RetrieveEngine, RetrieveOptions}
import core.sort.SortEngine
import core.settings.IvySettings
import plugins.resolver.{ChainResolver, FileSystemResolver, IBiblioResolver, URLResolver}
import util.{DefaultMessageLogger, Message}

import BootConfiguration._

sealed trait UpdateTarget extends NotNull { def tpe: String }
final object UpdateScala extends UpdateTarget { def tpe = "scala" }
final case class UpdateApp(id: Application) extends UpdateTarget { def tpe = "app" }

final class UpdateConfiguration(val bootDirectory: File, val scalaVersion: String, val repositories: Seq[Repository]) extends NotNull

/** Ensures that the Scala and application jars exist for the given versions or else downloads them.*/
final class Update(config: UpdateConfiguration)
{
	import config.{bootDirectory, repositories, scalaVersion}
	bootDirectory.mkdirs

	private def logFile = new File(bootDirectory, UpdateLogName)
	private val logWriter = new PrintWriter(new FileWriter(logFile))

	private lazy val settings =
	{
		val settings = new IvySettings
		addResolvers(settings)
		settings.setDefaultConflictManager(settings.getConflictManager(ConflictManagerName))
		settings.setBaseDir(bootDirectory)
		settings
	}
	private lazy val ivy = Ivy.newInstance(settings)

	/** The main entry point of this class for use by the Update module.  It runs Ivy */
	def apply(target: UpdateTarget)
	{
		Message.setDefaultLogger(new SbtIvyLogger(logWriter))
		ivy.pushContext()
		try { update(target) }
		catch
		{
			case e: Exception =>
				e.printStackTrace(logWriter)
				log(e.toString)
				println("  (see " + logFile + " for complete log)")
		}
		finally
		{
			logWriter.close()
			ivy.popContext()
		}
	}
	/** Runs update for the specified target (updates either the scala or appliciation jars for building the project) */
	private def update(target: UpdateTarget)
	{
		import IvyConfiguration.Visibility.PUBLIC
		// the actual module id here is not that important
		val moduleID = new DefaultModuleDescriptor(createID(SbtOrg, "boot-" + target.tpe, "1.0"), "release", null, false)
		moduleID.setLastModified(System.currentTimeMillis)
		moduleID.addConfiguration(new IvyConfiguration(DefaultIvyConfiguration, PUBLIC, "", Array(), true, null))
		// add dependencies based on which target needs updating
		target match
		{
			case UpdateScala =>
				addDependency(moduleID, ScalaOrg, CompilerModuleName, scalaVersion, "default")
				addDependency(moduleID, ScalaOrg, LibraryModuleName, scalaVersion, "default")
			case UpdateApp(app) =>
				val resolvedName = if(app.crossVersioned) app.name + "_" + scalaVersion else app.name
				addDependency(moduleID, app.groupID, resolvedName, app.getVersion, "runtime(default)")
		}
		update(moduleID, target)
	}
	/** Runs the resolve and retrieve for the given moduleID, which has had its dependencies added already. */
	private def update(moduleID: DefaultModuleDescriptor,  target: UpdateTarget)
	{
		val eventManager = new EventManager
		resolve(eventManager, moduleID)
		retrieve(eventManager, moduleID, target)
	}
	private def createID(organization: String, name: String, revision: String) =
		ModuleRevisionId.newInstance(organization, name, revision)
	/** Adds the given dependency to the default configuration of 'moduleID'. */
	private def addDependency(moduleID: DefaultModuleDescriptor, organization: String, name: String, revision: String, conf: String)
	{
		val dep = new DefaultDependencyDescriptor(moduleID, createID(organization, name, revision), false, false, true)
		dep.addDependencyConfiguration(DefaultIvyConfiguration, conf)
		moduleID.addDependency(dep)
	}
	private def resolve(eventManager: EventManager, module: ModuleDescriptor)
	{
		val resolveOptions = new ResolveOptions
		  // this reduces the substantial logging done by Ivy, including the progress dots when downloading artifacts
		resolveOptions.setLog(LogOptions.LOG_DOWNLOAD_ONLY)
		val resolveEngine = new ResolveEngine(settings, eventManager, new SortEngine(settings))
		val resolveReport = resolveEngine.resolve(module, resolveOptions)
		if(resolveReport.hasError)
		{
			logExceptions(resolveReport)
			println(Set(resolveReport.getAllProblemMessages.toArray: _*).mkString(System.getProperty("line.separator")))
			throw new BootException("Error retrieving required libraries")
		}
	}
	/** Exceptions are logged to the update log file. */
	private def logExceptions(report: ResolveReport)
	{
		for(unresolved <- report.getUnresolvedDependencies)
		{
			val problem = unresolved.getProblem
			if(problem != null)
				problem.printStackTrace(logWriter)
        }
	}
	/** Retrieves resolved dependencies using the given target to determine the location to retrieve to. */
	private def retrieve(eventManager: EventManager, module: ModuleDescriptor,  target: UpdateTarget)
	{
		val retrieveOptions = new RetrieveOptions
		val retrieveEngine = new RetrieveEngine(settings, eventManager)
		val pattern =
			target match
			{
				case UpdateScala => scalaRetrievePattern
				case UpdateApp(app) => appRetrievePattern(app.toID)
			}
		retrieveEngine.retrieve(module.getModuleRevisionId, baseDirectoryName(scalaVersion) + "/" + pattern, retrieveOptions)
	}
	/** Add the scala tools repositories and a URL resolver to download sbt from the Google code project.*/
	private def addResolvers(settings: IvySettings)
	{
		val newDefault = new ChainResolver
		newDefault.setName("redefined-public")
		if(repositories.isEmpty) throw new BootException("No repositories defined.")
		repositories.foreach(repo => newDefault.add(toIvyRepository(settings, repo)))
		onDefaultRepositoryCacheManager(settings)(_.setUseOrigin(true))
		settings.addResolver(newDefault)
		settings.setDefaultResolver(newDefault.getName)
	}
	private def toIvyRepository(settings: IvySettings, repo: Repository) =
	{
		import Repository.{Ivy, Maven, Predefined}
		import Predefined._
		repo match
		{
			case Maven(id, url) => mavenResolver(id, url)
			case Ivy(id, url, pattern) => urlResolver(id, url, pattern)
			case Predefined(Local) => localResolver(settings.getDefaultIvyUserDir.getAbsolutePath)
			case Predefined(MavenLocal) => mavenLocal
			case Predefined(MavenCentral) => mavenMainResolver
			case Predefined(ScalaToolsReleases) => mavenResolver("Scala-Tools Maven2 Repository", "http://scala-tools.org/repo-releases")
			case Predefined(ScalaToolsSnapshots) => mavenResolver("Scala-Tools Maven2 Snapshots Repository", "http://scala-tools.org/repo-snapshots")
		}
	}
	private def onDefaultRepositoryCacheManager(settings: IvySettings)(f: DefaultRepositoryCacheManager => Unit)
	{
		settings.getDefaultRepositoryCacheManager match
		{
			case manager: DefaultRepositoryCacheManager => f(manager)
			case _ => ()
		}
	}
	/** Uses the pattern defined in BuildConfiguration to download sbt from Google code.*/
	private def urlResolver(id: String, base: String, pattern: String) =
	{
		val resolver = new URLResolver
		resolver.setName(id)
		val adjusted = (if(base.endsWith("/")) base else (base + "/") ) + pattern
		resolver.addIvyPattern(adjusted)
		resolver.addArtifactPattern(adjusted)
		resolver
	}
	private def mavenLocal = mavenResolver("Maven2 Local", "file://" + System.getProperty("user.home") + "/.m2/repository/")
	/** Creates a maven-style resolver.*/
	private def mavenResolver(name: String, root: String) =
	{
		val resolver = defaultMavenResolver(name)
		resolver.setRoot(root)
		resolver
	}
	/** Creates a resolver for Maven Central.*/
	private def mavenMainResolver = defaultMavenResolver("Maven Central")
	/** Creates a maven-style resolver with the default root.*/
	private def defaultMavenResolver(name: String) =
	{
		val resolver = new IBiblioResolver
		resolver.setName(name)
		resolver.setM2compatible(true)
		resolver
	}
	private def localResolver(ivyUserDirectory: String) =
	{
		val localIvyRoot = ivyUserDirectory + "/local"
		val resolver = new FileSystemResolver
		resolver.setName(LocalIvyName)
		resolver.addIvyPattern(localIvyRoot + "/" + LocalIvyPattern)
		resolver.addArtifactPattern(localIvyRoot + "/" + LocalArtifactPattern)
		resolver
	}
	/** Logs the given message to a file and to the console. */
	private def log(msg: String) =
	{
		try { logWriter.println(msg) }
		catch { case e: Exception => System.err.println("Error writing to update log file: " + e.toString) }
		println(msg)
	}
}
/** A custom logger for Ivy to ignore the messages about not finding classes
* intentionally filtered using proguard. */
private final class SbtIvyLogger(logWriter: PrintWriter) extends DefaultMessageLogger(Message.MSG_INFO) with NotNull
{
	private val ignorePrefix = "impossible to define"
	override def log(msg: String, level: Int)
	{
		logWriter.println(msg)
		if(level <= getLevel && msg != null && !msg.startsWith(ignorePrefix))
			System.out.println(msg)
	}
	override def rawlog(msg: String, level: Int) { log(msg, level) }
}