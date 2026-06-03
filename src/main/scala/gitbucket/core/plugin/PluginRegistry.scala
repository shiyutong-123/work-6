package gitbucket.core.plugin

import java.io.{File, FilenameFilter}
import java.net.{URL, URLClassLoader}
import java.nio.file.{Files, Paths, StandardWatchEventKinds}
import java.sql.Connection
import java.util.{Base64, ServiceLoader}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.servlet.ServletContext
import com.github.zafarkhaja.semver.Version
import gitbucket.core.controller.{Context, ControllerBase}
import gitbucket.core.model.{Account, Issue}
import gitbucket.core.service.ProtectedBranchService.ProtectedBranchReceiveHook
import gitbucket.core.service.RepositoryService.RepositoryInfo
import gitbucket.core.service.SystemSettingsService
import gitbucket.core.service.SystemSettingsService.SystemSettings
import gitbucket.core.util.{ConfigUtil, DatabaseConfig}
import gitbucket.core.util.Directory.*
import io.github.gitbucket.solidbase.Solidbase
import io.github.gitbucket.solidbase.manager.JDBCVersionManager
import io.github.gitbucket.solidbase.model.Module
import org.apache.commons.io.FileUtils
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.slf4j.LoggerFactory
import play.twirl.api.Html

import scala.jdk.CollectionConverters.*

class PluginRegistry {

  private val plugins = new ConcurrentLinkedQueue[PluginInfo]
  private val javaScripts = new ConcurrentLinkedQueue[(String, String)]
  private val controllers = new ConcurrentLinkedQueue[(ControllerBase, String)]
  private val anonymousAccessiblePaths = new ConcurrentLinkedQueue[String]
  private val images = new ConcurrentHashMap[String, String]
  private val renderers = new ConcurrentHashMap[String, Renderer]
  renderers.put("md", MarkdownRenderer)
  renderers.put("markdown", MarkdownRenderer)
  private val repositoryRoutings = new ConcurrentLinkedQueue[GitRepositoryRouting]
  private val accountHooks = new ConcurrentLinkedQueue[AccountHook]
  private val receiveHooks = new ConcurrentLinkedQueue[ReceiveHook]
  receiveHooks.add(new ProtectedBranchReceiveHook())
  private val repositoryHooks = new ConcurrentLinkedQueue[RepositoryHook]
  private val issueHooks = new ConcurrentLinkedQueue[IssueHook]
  private val pullRequestHooks = new ConcurrentLinkedQueue[PullRequestHook]
  private val repositoryHeaders = new ConcurrentLinkedQueue[(RepositoryInfo, Context) => Option[Html]]
  private val globalMenus = new ConcurrentLinkedQueue[(Context) => Option[Link]]
  private val repositoryMenus = new ConcurrentLinkedQueue[(RepositoryInfo, Context) => Option[Link]]
  private val repositorySettingTabs = new ConcurrentLinkedQueue[(RepositoryInfo, Context) => Option[Link]]
  private val profileTabs = new ConcurrentLinkedQueue[(Account, Context) => Option[Link]]
  private val systemSettingMenus = new ConcurrentLinkedQueue[(Context) => Option[Link]]
  private val accountSettingMenus = new ConcurrentLinkedQueue[(Context) => Option[Link]]
  private val dashboardTabs = new ConcurrentLinkedQueue[(Context) => Option[Link]]
  private val issueSidebars = new ConcurrentLinkedQueue[(Issue, RepositoryInfo, Context) => Option[Html]]
  private val assetsMappings = new ConcurrentLinkedQueue[(String, String, ClassLoader)]
  private val textDecorators = new ConcurrentLinkedQueue[TextDecorator]
  private val suggestionProviders = new ConcurrentLinkedQueue[SuggestionProvider]
  suggestionProviders.add(new UserNameSuggestionProvider())
  suggestionProviders.add(new IssueSuggestionProvider())
  private val sshCommandProviders = new ConcurrentLinkedQueue[PartialFunction[String, ChannelSession => Command]]()

  def addPlugin(pluginInfo: PluginInfo): Unit = plugins.add(pluginInfo)

  def getPlugins(): List[PluginInfo] = plugins.asScala.toList

  def addImage(id: String, bytes: Array[Byte]): Unit = {
    val encoded = Base64.getEncoder.encodeToString(bytes)
    images.put(id, encoded)
  }

  def getImage(id: String): String = images.get(id)

  def addController(path: String, controller: ControllerBase): Unit = controllers.add((controller, path))

  def getControllers(): Seq[(ControllerBase, String)] = controllers.asScala.toSeq

  def addAnonymousAccessiblePath(path: String): Unit = anonymousAccessiblePaths.add(path)

  def getAnonymousAccessiblePaths(): Seq[String] = anonymousAccessiblePaths.asScala.toSeq

  def addJavaScript(path: String, script: String): Unit =
    javaScripts.add((path, script))

  def getJavaScript(currentPath: String): List[String] =
    javaScripts.asScala.filter(x => currentPath.matches(x._1)).toList.map(_._2)

  def addRenderer(extension: String, renderer: Renderer): Unit = renderers.put(extension, renderer)

  def getRenderer(extension: String): Renderer = renderers.asScala.getOrElse(extension, DefaultRenderer)

  def renderableExtensions: Seq[String] = renderers.keys.asScala.toSeq

  def addRepositoryRouting(routing: GitRepositoryRouting): Unit = repositoryRoutings.add(routing)

  def getRepositoryRoutings(): Seq[GitRepositoryRouting] = repositoryRoutings.asScala.toSeq

  def getRepositoryRouting(repositoryPath: String): Option[GitRepositoryRouting] = {
    PluginRegistry().getRepositoryRoutings().find {
      case GitRepositoryRouting(urlPath, _, _) =>
        repositoryPath.matches("/" + urlPath + "(/.*)?")
    }
  }

  def addAccountHook(accountHook: AccountHook): Unit = accountHooks.add(accountHook)

  def getAccountHooks: Seq[AccountHook] = accountHooks.asScala.toSeq

  def addReceiveHook(commitHook: ReceiveHook): Unit = receiveHooks.add(commitHook)

  def getReceiveHooks: Seq[ReceiveHook] = receiveHooks.asScala.toSeq

  def addRepositoryHook(repositoryHook: RepositoryHook): Unit = repositoryHooks.add(repositoryHook)

  def getRepositoryHooks: Seq[RepositoryHook] = repositoryHooks.asScala.toSeq

  def addIssueHook(issueHook: IssueHook): Unit = issueHooks.add(issueHook)

  def getIssueHooks: Seq[IssueHook] = issueHooks.asScala.toSeq

  def addPullRequestHook(pullRequestHook: PullRequestHook): Unit = pullRequestHooks.add(pullRequestHook)

  def getPullRequestHooks: Seq[PullRequestHook] = pullRequestHooks.asScala.toSeq

  def addRepositoryHeader(repositoryHeader: (RepositoryInfo, Context) => Option[Html]): Unit =
    repositoryHeaders.add(repositoryHeader)

  def getRepositoryHeaders: Seq[(RepositoryInfo, Context) => Option[Html]] = repositoryHeaders.asScala.toSeq

  def addGlobalMenu(globalMenu: (Context) => Option[Link]): Unit = globalMenus.add(globalMenu)

  def getGlobalMenus: Seq[(Context) => Option[Link]] = globalMenus.asScala.toSeq

  def addRepositoryMenu(repositoryMenu: (RepositoryInfo, Context) => Option[Link]): Unit =
    repositoryMenus.add(repositoryMenu)

  def getRepositoryMenus: Seq[(RepositoryInfo, Context) => Option[Link]] = repositoryMenus.asScala.toSeq

  def addRepositorySettingTab(repositorySettingTab: (RepositoryInfo, Context) => Option[Link]): Unit =
    repositorySettingTabs.add(repositorySettingTab)

  def getRepositorySettingTabs: Seq[(RepositoryInfo, Context) => Option[Link]] = repositorySettingTabs.asScala.toSeq

  def addProfileTab(profileTab: (Account, Context) => Option[Link]): Unit = profileTabs.add(profileTab)

  def getProfileTabs: Seq[(Account, Context) => Option[Link]] = profileTabs.asScala.toSeq

  def addSystemSettingMenu(systemSettingMenu: (Context) => Option[Link]): Unit =
    systemSettingMenus.add(systemSettingMenu)

  def getSystemSettingMenus: Seq[(Context) => Option[Link]] = systemSettingMenus.asScala.toSeq

  def addAccountSettingMenu(accountSettingMenu: (Context) => Option[Link]): Unit =
    accountSettingMenus.add(accountSettingMenu)

  def getAccountSettingMenus: Seq[(Context) => Option[Link]] = accountSettingMenus.asScala.toSeq

  def addDashboardTab(dashboardTab: (Context) => Option[Link]): Unit = dashboardTabs.add(dashboardTab)

  def getDashboardTabs: Seq[(Context) => Option[Link]] = dashboardTabs.asScala.toSeq

  def addIssueSidebar(issueSidebar: (Issue, RepositoryInfo, Context) => Option[Html]): Unit =
    issueSidebars.add(issueSidebar)

  def getIssueSidebars: Seq[(Issue, RepositoryInfo, Context) => Option[Html]] = issueSidebars.asScala.toSeq

  def addAssetsMapping(assetsMapping: (String, String, ClassLoader)): Unit = assetsMappings.add(assetsMapping)

  def getAssetsMappings: Seq[(String, String, ClassLoader)] = assetsMappings.asScala.toSeq

  def addTextDecorator(textDecorator: TextDecorator): Unit = textDecorators.add(textDecorator)

  def getTextDecorators: Seq[TextDecorator] = textDecorators.asScala.toSeq

  def addSuggestionProvider(suggestionProvider: SuggestionProvider): Unit = suggestionProviders.add(suggestionProvider)

  def getSuggestionProviders: Seq[SuggestionProvider] = suggestionProviders.asScala.toSeq

  def addSshCommandProvider(sshCommandProvider: PartialFunction[String, ChannelSession => Command]): Unit =
    sshCommandProviders.add(sshCommandProvider)

  def getSshCommandProviders: Seq[PartialFunction[String, ChannelSession => Command]] =
    sshCommandProviders.asScala.toSeq
}

/**
 * Provides entry point to PluginRegistry.
 */
object PluginRegistry {

  private val logger = LoggerFactory.getLogger(classOf[PluginRegistry])
  private val pluginServiceResource = s"META-INF/services/${classOf[Plugin].getName}"

  private var instance = new PluginRegistry()
  private var watcherThreads = Vector.empty[PluginWatchThread]
  private var watchedDirectories = Vector.empty[String]
  private var externalPluginCache = Map.empty[String, ExternalPluginCacheEntry]
  private var activePlugins = Vector.empty[ActivePluginRuntime]
  private var cachedSnapshot = PluginSnapshot.empty

  /**
   * Returns the PluginRegistry singleton instance.
   */
  def apply(): PluginRegistry = instance

  /**
   * Reload all plugins.
   */
  def reload(context: ServletContext, settings: SystemSettings, conn: Connection): Unit = synchronized {
    refresh(context, settings, conn, forceReload = false)
  }

  /**
   * Uninstall a specified plugin.
   */
  def uninstall(pluginId: String, context: ServletContext, settings: SystemSettings, conn: Connection): Unit =
    synchronized {
      shutdown(context, settings)

      new File(PluginHome)
        .listFiles((_: File, name: String) => {
          name.startsWith(s"gitbucket-${pluginId}-plugin") && name.endsWith(".jar")
        })
        .foreach(_.delete())

      initialize(context, settings, conn)
    }

  private def listPluginJars(dir: File): Seq[File] = {
    Option(dir.listFiles(new FilenameFilter {
      override def accept(dir: File, name: String): Boolean = name.endsWith(".jar")
    }))
      .map(_.toSeq)
      .getOrElse(Nil)
      .sortBy(x => Version.parse(getPluginVersion(x.getName)))
      .reverse
  }

  private lazy val extraPluginDir: Option[String] = ConfigUtil.getConfigValue[String]("gitbucket.pluginDir")

  private def getGitBucketVersion(pluginJarFileName: String): Option[String] = {
    val regex = ".+-gitbucket_(\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?)-.+".r
    pluginJarFileName match {
      case regex(all, _) => Some(all)
      case _             => None
    }
  }

  private def getPluginVersion(pluginJarFileName: String): String = {
    val regex = ".+-((\\d+)\\.(\\d+)(\\.(\\d+))?(-SNAPSHOT)?)\\.jar$".r
    pluginJarFileName match {
      case regex(all, major, minor, _, patch, modifier) =>
        if (patch != null) {
          all
        } else {
          s"${major}.${minor}.0" + (if (modifier == null) "" else modifier)
        }
      case _ => "0.0.0"
    }
  }

  /**
   * Initializes all installed plugins.
   */
  def initialize(context: ServletContext, settings: SystemSettings, conn: Connection): Unit = synchronized {
    refresh(context, settings, conn, forceReload = true)
  }

  def shutdown(context: ServletContext, settings: SystemSettings): Unit = synchronized {
    stopWatchers()
    shutdownActivePlugins(context, settings)
    closeObsoleteCacheEntries(Map.empty)
    externalPluginCache = Map.empty
    cachedSnapshot = PluginSnapshot.empty
    activePlugins = Vector.empty
    instance = new PluginRegistry()
  }

  def getPluginInfoFromClassLoader(classLoader: ClassLoader): Option[PluginInfo] = {
    instance
      .getPlugins()
      .find { info =>
        info.classLoader.equals(classLoader)
      }
  }

  private def refresh(context: ServletContext, settings: SystemSettings, conn: Connection, forceReload: Boolean): Unit = {
    val discovery = discoverPlugins()

    if (!forceReload && discovery.snapshot == cachedSnapshot) {
      logger.info("No plugin changes detected. Skip reloading plugins.")
      restartWatchers(context, discovery.watchDirectories)
      return
    }

    shutdownActivePlugins(context, settings)

    val newRegistry = new PluginRegistry()
    val manager = new JDBCVersionManager(conn)
    val initializedPlugins = initializePlugins(discovery.candidates, newRegistry, context, settings, conn, manager)

    instance = newRegistry
    activePlugins = initializedPlugins
    cachedSnapshot = discovery.snapshot
    closeObsoleteCacheEntries(discovery.externalCache)
    externalPluginCache = discovery.externalCache
    restartWatchers(context, discovery.watchDirectories)
  }

  private def initializePlugins(
    candidates: Seq[PluginCandidate],
    registry: PluginRegistry,
    context: ServletContext,
    settings: SystemSettings,
    conn: Connection,
    manager: JDBCVersionManager
  ): Vector[ActivePluginRuntime] = {
    val active = Vector.newBuilder[ActivePluginRuntime]

    candidates.foreach { candidate =>
      val plugin = candidate.plugin
      val pluginId = plugin.pluginId

      registry.getPlugins().find(_.pluginId == pluginId) match {
        case Some(existing) =>
          logger.warn(s"Plugin ${pluginId} is duplicated. ${existing.pluginJar.getName} is available.")
        case None =>
          try {
            logger.info(s"Initialize ${candidate.pluginJar.getName}")
            val solidbase = new Solidbase()
            solidbase
              .migrate(
                conn,
                candidate.classLoader,
                DatabaseConfig.liquiDriver,
                new Module(plugin.pluginId, plugin.versions*)
              )
            conn.commit()

            val databaseVersion = manager.getCurrentVersion(plugin.pluginId)
            val pluginVersion = plugin.versions.last.getVersion
            if (databaseVersion != pluginVersion) {
              throw new IllegalStateException(
                s"Plugin version is ${pluginVersion}, but database version is ${databaseVersion}"
              )
            }

            plugin.initialize(registry, context, settings)
            registry.addPlugin(
              PluginInfo(
                pluginId = plugin.pluginId,
                pluginName = plugin.pluginName,
                pluginVersion = pluginVersion,
                gitbucketVersion = getGitBucketVersion(candidate.pluginJar.getName),
                description = plugin.description,
                pluginClass = plugin,
                pluginJar = candidate.pluginJar,
                classLoader = candidate.classLoader
              )
            )
            active += ActivePluginRuntime(plugin)
          } catch {
            case e: Throwable =>
              logger.error(s"Error during plugin initialization: ${candidate.pluginJar.getName}", e)
          }
      }
    }

    active.result()
  }

  private def discoverPlugins(): PluginDiscovery = {
    val externalJars = pluginDirectories.flatMap(listPluginJars)
    val nextExternalCache = scala.collection.mutable.LinkedHashMap.empty[String, ExternalPluginCacheEntry]

    val externalCandidates = externalJars.flatMap { pluginJar =>
      val sourceJar = canonicalFile(pluginJar)
      val cacheKey = sourceJar.getAbsolutePath
      val fingerprint = PluginArtifactFingerprint(cacheKey, sourceJar.length(), sourceJar.lastModified())
      val cacheEntry = externalPluginCache.get(cacheKey) match {
        case Some(existing) if existing.fingerprint == fingerprint => existing
        case _                                                    => createExternalPluginCacheEntry(sourceJar, fingerprint)
      }

      nextExternalCache.update(cacheKey, cacheEntry)
      loadExternalPlugins(cacheEntry)
    }

    val classpathCandidates = loadClasspathPlugins()
    val candidates = externalCandidates ++ classpathCandidates

    val snapshot = PluginSnapshot(
      candidates
        .map(candidate => PluginSnapshotEntry(
          candidate.pluginJar.getAbsolutePath,
          candidate.plugin.getClass.getName,
          candidate.pluginJar.length(),
          candidate.pluginJar.lastModified()
        ))
        .distinct
        .sortBy(entry => (entry.sourcePath, entry.providerClassName))
    )

    val watchDirectories = (
      pluginDirectories ++ classpathCandidates.flatMap(candidate => Option(candidate.pluginJar.getParentFile))
    ).flatMap(normalizeDirectory)
      .distinct
      .sorted

    PluginDiscovery(candidates, snapshot, nextExternalCache.toMap, watchDirectories)
  }

  private def loadExternalPlugins(cacheEntry: ExternalPluginCacheEntry): Seq[PluginCandidate] = {
    val plugins = {
      val serviceLoaded = loadPluginsFromServiceLoader(cacheEntry.classLoader)
      if (serviceLoaded.nonEmpty) serviceLoaded else loadLegacyPlugin(cacheEntry.classLoader).toSeq
    }

    if (plugins.isEmpty) {
      logger.warn(s"No plugin provider found in ${cacheEntry.sourceJar.getName}")
    }

    plugins.map { plugin =>
      PluginCandidate(
        plugin = plugin,
        pluginJar = cacheEntry.sourceJar,
        classLoader = cacheEntry.classLoader
      )
    }
  }

  private def loadClasspathPlugins(): Seq[PluginCandidate] = {
    val classLoader = Thread.currentThread.getContextClassLoader
    loadPluginsFromServiceLoader(classLoader)
      .map { plugin =>
        val pluginJar = resolvePluginSource(plugin).getOrElse(new File(plugin.getClass.getName))
        PluginCandidate(
          plugin = plugin,
          pluginJar = pluginJar,
          classLoader = plugin.getClass.getClassLoader
        )
      }
      .sortBy(candidate => (candidate.pluginJar.getAbsolutePath, candidate.plugin.getClass.getName))
  }

  private def loadPluginsFromServiceLoader(classLoader: ClassLoader): Seq[Plugin] = {
    val serviceLoader = ServiceLoader.load(classOf[Plugin], classLoader)
    val iterator = serviceLoader.iterator()
    val plugins = Vector.newBuilder[Plugin]

    while ({
      try {
        iterator.hasNext
      } catch {
        case e: Throwable =>
          logger.error(s"Error during plugin discovery from ${classLoader}", e)
          false
      }
    }) {
      try {
        plugins += iterator.next()
      } catch {
        case e: Throwable =>
          logger.error(s"Error during plugin instantiation from ${classLoader}", e)
      }
    }

    plugins.result()
  }

  private def loadLegacyPlugin(classLoader: ClassLoader): Option[Plugin] = {
    try {
      Some(classLoader.loadClass("Plugin").getDeclaredConstructor().newInstance().asInstanceOf[Plugin])
    } catch {
      case _: ClassNotFoundException => None
      case e: Throwable =>
        logger.error(s"Error during legacy plugin instantiation from ${classLoader}", e)
        None
    }
  }

  private def pluginDirectories: Seq[File] =
    extraPluginDir.map(dir => canonicalFile(new File(dir))).toSeq ++ Seq(canonicalFile(new File(PluginHome)))

  private def createExternalPluginCacheEntry(
    sourceJar: File,
    fingerprint: PluginArtifactFingerprint
  ): ExternalPluginCacheEntry = {
    val installedDir = new File(PluginHome, ".installed")
    FileUtils.forceMkdir(installedDir)

    val artifactDir = new File(installedDir, Integer.toHexString(sourceJar.getAbsolutePath.hashCode))
    FileUtils.forceMkdir(artifactDir)

    val stagedJar = new File(artifactDir, s"${fingerprint.lastModified}-${fingerprint.size}-${sourceJar.getName}")
    if (!stagedJar.exists()) {
      FileUtils.copyFile(sourceJar, stagedJar)
    }

    val classLoader = new PluginClassLoader(Array(stagedJar.toURI.toURL), Thread.currentThread.getContextClassLoader)
    ExternalPluginCacheEntry(sourceJar.getAbsolutePath, sourceJar, fingerprint, stagedJar, classLoader)
  }

  private def closeObsoleteCacheEntries(nextCache: Map[String, ExternalPluginCacheEntry]): Unit = {
    externalPluginCache.foreach { case (key, entry) =>
      nextCache.get(key) match {
        case Some(nextEntry) if nextEntry.eq(entry) => ()
        case _                                      => closeCacheEntry(entry)
      }
    }
  }

  private def closeCacheEntry(entry: ExternalPluginCacheEntry): Unit = {
    try {
      entry.classLoader.close()
    } catch {
      case e: Exception => logger.error(s"Error during plugin class loader shutdown: ${entry.sourceJar.getName}", e)
    }

    if (entry.stagedJar.exists()) {
      entry.stagedJar.delete()
      Option(entry.stagedJar.getParentFile)
        .filter(dir => Option(dir.list()).exists(_.isEmpty))
        .foreach(_.delete())
    }
  }

  private def shutdownActivePlugins(context: ServletContext, settings: SystemSettings): Unit = {
    activePlugins.foreach { runtime =>
      try {
        runtime.plugin.shutdown(instance, context, settings)
      } catch {
        case e: Exception =>
          val pluginName = resolvePluginSource(runtime.plugin)
            .map(_.getName)
            .getOrElse(runtime.plugin.getClass.getName)
          logger.error(s"Error during plugin shutdown: ${pluginName}", e)
      }
    }
    activePlugins = Vector.empty
  }

  private def restartWatchers(context: ServletContext, directories: Seq[String]): Unit = {
    val normalized = directories.flatMap(dir => normalizeDirectory(new File(dir))).distinct.sorted.toVector
    if (normalized == watchedDirectories) {
      return
    }

    stopWatchers()
    watchedDirectories = normalized
    watcherThreads = normalized.map { dir =>
      val watcher = new PluginWatchThread(context, dir)
      watcher.start()
      watcher
    }
  }

  private def stopWatchers(): Unit = {
    watcherThreads.foreach(_.interrupt())
    watcherThreads = Vector.empty
    watchedDirectories = Vector.empty
  }

  private def canonicalFile(file: File): File = {
    try {
      file.getCanonicalFile
    } catch {
      case _: Exception => file.getAbsoluteFile
    }
  }

  private def normalizeDirectory(dir: File): Option[String] = {
    val normalized = canonicalFile(dir)
    val pluginHomeDir = canonicalFile(new File(PluginHome)).getAbsolutePath
    Option(normalized)
      .filter(_.exists() || normalized.getAbsolutePath == pluginHomeDir)
      .filterNot(_.getName == ".installed")
      .map(_.getAbsolutePath)
  }

  private def resolvePluginSource(plugin: Plugin): Option[File] = {
    Option(plugin.getClass.getProtectionDomain)
      .flatMap(domain => Option(domain.getCodeSource))
      .flatMap(codeSource => Option(codeSource.getLocation))
      .flatMap(urlToFile)
      .map(canonicalFile)
  }

  private def urlToFile(url: URL): Option[File] = {
    try {
      if (url.getProtocol == "file") Some(new File(url.toURI)) else None
    } catch {
      case _: Exception => None
    }
  }

  private class PluginClassLoader(urls: Array[URL], parent: ClassLoader)
      extends URLClassLoader(urls, parent) {
    override def getResource(name: String): URL = {
      if (name == pluginServiceResource) findResource(name) else super.getResource(name)
    }

    override def getResources(name: String): java.util.Enumeration[URL] = {
      if (name == pluginServiceResource) findResources(name) else super.getResources(name)
    }
  }
}

case class Link(
  id: String,
  label: String,
  path: String,
  icon: Option[String] = None
)

class PluginInfoBase(
  val pluginId: String,
  val pluginName: String,
  val pluginVersion: String,
  val gitbucketVersion: Option[String],
  val description: String
)

case class PluginInfo(
  override val pluginId: String,
  override val pluginName: String,
  override val pluginVersion: String,
  override val gitbucketVersion: Option[String],
  override val description: String,
  pluginClass: Plugin,
  pluginJar: File,
  classLoader: ClassLoader
) extends PluginInfoBase(pluginId, pluginName, pluginVersion, gitbucketVersion, description)

private case class PluginCandidate(
  plugin: Plugin,
  pluginJar: File,
  classLoader: ClassLoader
)

private case class ActivePluginRuntime(
  plugin: Plugin
)

private case class PluginArtifactFingerprint(
  sourcePath: String,
  size: Long,
  lastModified: Long
)

private case class PluginSnapshotEntry(
  sourcePath: String,
  providerClassName: String,
  size: Long,
  lastModified: Long
)

private case class PluginSnapshot(entries: Seq[PluginSnapshotEntry])

private object PluginSnapshot {
  val empty: PluginSnapshot = PluginSnapshot(Nil)
}

private case class ExternalPluginCacheEntry(
  key: String,
  sourceJar: File,
  fingerprint: PluginArtifactFingerprint,
  stagedJar: File,
  classLoader: URLClassLoader
)

private case class PluginDiscovery(
  candidates: Seq[PluginCandidate],
  snapshot: PluginSnapshot,
  externalCache: Map[String, ExternalPluginCacheEntry],
  watchDirectories: Seq[String]
)

class PluginWatchThread(context: ServletContext, dir: String) extends Thread with SystemSettingsService {
  import gitbucket.core.model.Profile.profile.blockingApi.*

  private val logger = LoggerFactory.getLogger(classOf[PluginWatchThread])

  override def run(): Unit = {
    val path = Paths.get(dir)
    if (!Files.exists(path)) {
      Files.createDirectories(path)
    }
    val fs = path.getFileSystem
    val watcher = fs.newWatchService

    val watchKey = path.register(
      watcher,
      StandardWatchEventKinds.ENTRY_CREATE,
      StandardWatchEventKinds.ENTRY_MODIFY,
      StandardWatchEventKinds.ENTRY_DELETE,
      StandardWatchEventKinds.OVERFLOW
    )

    logger.info("Start PluginWatchThread: " + path)

    try {
      while (watchKey.isValid) {
        val detectedWatchKey = watcher.take()
        val events = detectedWatchKey.pollEvents.asScala.filter { event =>
          val name = event.context.toString
          name != ".installed" && !name.endsWith(".bak") && (name.endsWith(".jar") || event.kind == StandardWatchEventKinds.OVERFLOW)
        }
        if (events.nonEmpty) {
          events.foreach { event =>
            logger.info(s"${event.kind}: ${event.context}")
          }
          new Thread {
            override def run(): Unit = {
              gitbucket.core.servlet.Database() withTransaction { session =>
                logger.info("Reloading plugins...")
                PluginRegistry.reload(context, loadSystemSettings(), session.conn)
                logger.info("Reloading finished.")
              }
            }
          }.start()
        }
        detectedWatchKey.reset()
      }
    } catch {
      case _: InterruptedException => watchKey.cancel()
    }

    logger.info("Shutdown PluginWatchThread")
  }

}
