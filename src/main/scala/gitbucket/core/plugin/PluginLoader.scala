package gitbucket.core.plugin

import java.io.{File, FilenameFilter}
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap

import com.github.zafarkhaja.semver.Version
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters.*

/**
 * Plugin loader using Java ServiceLoader with caching mechanism.
 */
class PluginLoader {

  private val logger = LoggerFactory.getLogger(classOf[PluginLoader])

  private val pluginCache = new ConcurrentHashMap[String, CachedPlugin]()

  case class CachedPlugin(
    plugin: Plugin,
    classLoader: URLClassLoader,
    pluginJar: File,
    lastModified: Long
  )

  /**
   * Load plugins from given directories using ServiceLoader.
   */
  def loadPlugins(
    pluginDirs: Seq[File],
    installedDir: File,
    parentClassLoader: ClassLoader
  ): Seq[LoadedPlugin] = {

    val pluginJars = pluginDirs.flatMap(dir => listPluginJars(dir))

    pluginJars.map { pluginJar =>
      try {
        val cacheKey = pluginJar.getAbsolutePath
        val currentLastModified = pluginJar.lastModified()

        pluginCache.get(cacheKey) match {
          case cached if cached != null && cached.lastModified == currentLastModified =>
            logger.info(s"Using cached plugin: ${pluginJar.getName}")
            LoadedPlugin(cached.plugin, cached.classLoader, pluginJar, isCached = true)

          case _ =>
            logger.info(s"Loading new or updated plugin: ${pluginJar.getName}")

            val installedJar = new File(installedDir, pluginJar.getName)
            FileUtils.copyFile(pluginJar, installedJar)

            val classLoader = new URLClassLoader(
              Array(installedJar.toURI.toURL),
              parentClassLoader
            )

            val serviceLoader = ServiceLoader.load(classOf[gitbucket.plugin.Plugin], classLoader)
            val plugins = serviceLoader.iterator().asScala.toList

            val plugin = plugins.headOption.getOrElse {
              classLoader.loadClass("Plugin").getDeclaredConstructor().newInstance().asInstanceOf[Plugin]
            }

            val cachedPlugin = CachedPlugin(plugin, classLoader, pluginJar, currentLastModified)
            pluginCache.put(cacheKey, cachedPlugin)

            LoadedPlugin(plugin, classLoader, pluginJar, isCached = false)
        }
      } catch {
        case e: Throwable =>
          logger.error(s"Failed to load plugin: ${pluginJar.getName}", e)
          throw e
      }
    }
  }

  /**
   * Clear plugin cache.
   */
  def clearCache(): Unit = {
    pluginCache.values().foreach { cached =>
      try {
        cached.classLoader.close()
      } catch {
        case e: Exception =>
          logger.error(s"Error closing class loader for plugin: ${cached.pluginJar.getName}", e)
      }
    }
    pluginCache.clear()
    logger.info("Plugin cache cleared")
  }

  /**
   * Clear cache for a specific plugin.
   */
  def clearCacheForPlugin(pluginJar: File): Unit = {
    val cacheKey = pluginJar.getAbsolutePath
    Option(pluginCache.remove(cacheKey)).foreach { cached =>
      try {
        cached.classLoader.close()
      } catch {
        case e: Exception =>
          logger.error(s"Error closing class loader for plugin: ${pluginJar.getName}", e)
      }
      logger.info(s"Cache cleared for plugin: ${pluginJar.getName}")
    }
  }

  /**
   * Get cached plugin status.
   */
  def getCachedPlugins: Seq[String] = {
    pluginCache.keys().asScala.toSeq
  }

  private def listPluginJars(dir: File): Seq[File] = {
    if (!dir.exists() || !dir.isDirectory) {
      Seq.empty
    } else {
      dir
        .listFiles(new FilenameFilter {
          override def accept(dir: File, name: String): Boolean = name.endsWith(".jar")
        })
        .toSeq
        .sortBy(x => Version.parse(getPluginVersion(x.getName)))
        .reverse
    }
  }

  private def getPluginVersion(pluginJarFileName: String): String = {
    val regex = """.+-((\d+)\.(\d+)(\.(\d+))?(-SNAPSHOT)?)\.jar$""".r
    pluginJarFileName match {
      case regex(all, _, _, _, _, _) => all
      case _ => "0.0.0"
    }
  }
}

/**
 * Represents a loaded plugin.
 */
case class LoadedPlugin(
  plugin: Plugin,
  classLoader: URLClassLoader,
  pluginJar: File,
  isCached: Boolean
)
