package gitbucket.core

import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.util.UUID
import gitbucket.core.model.Activity
import gitbucket.core.plugin.PluginRegistry
import gitbucket.core.service.SystemSettingsService.SystemSettings
import gitbucket.core.util.Directory.ActivityLog
import gitbucket.core.util.JDBCUtil
import io.github.gitbucket.solidbase.Solidbase
import io.github.gitbucket.solidbase.migration.{LiquibaseMigration, Migration}
import io.github.gitbucket.solidbase.model.{Module, Version}
import javax.servlet.ServletContext
import org.json4s.{Formats, NoTypeHints}
import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization.write
import org.slf4j.LoggerFactory

import java.util.logging.Level
import scala.util.Using

trait PluginLifecycle {

  def onInitialize(context: ServletContext, settings: SystemSettings, conn: Connection): Unit

  def onShutdown(context: ServletContext, settings: SystemSettings): Unit

  def onReload(context: ServletContext, settings: SystemSettings, conn: Connection): Unit
}

object PluginServiceDescriptor {
  val ServicePath = "META-INF/services/gitbucket.core.plugin.Plugin"
}

object GitBucketCoreModule
    extends Module(
      "gitbucket-core",
      new Version("4.0.0", new LiquibaseMigration("update/gitbucket-core_4.0.xml")),
      new Version("4.1.0"),
      new Version("4.2.0", new LiquibaseMigration("update/gitbucket-core_4.2.xml")),
      new Version("4.2.1"),
      new Version("4.3.0"),
      new Version("4.4.0"),
      new Version("4.5.0"),
      new Version("4.6.0", new LiquibaseMigration("update/gitbucket-core_4.6.xml")),
      new Version("4.7.0", new LiquibaseMigration("update/gitbucket-core_4.7.xml")),
      new Version("4.7.1"),
      new Version("4.8"),
      new Version("4.9.0", new LiquibaseMigration("update/gitbucket-core_4.9.xml")),
      new Version("4.10.0"),
      new Version("4.11.0", new LiquibaseMigration("update/gitbucket-core_4.11.xml")),
      new Version("4.12.0"),
      new Version("4.12.1"),
      new Version("4.13.0"),
      new Version("4.14.0", new LiquibaseMigration("update/gitbucket-core_4.14.xml")),
      new Version("4.14.1"),
      new Version("4.15.0"),
      new Version("4.16.0"),
      new Version("4.17.0"),
      new Version("4.18.0"),
      new Version("4.19.0"),
      new Version("4.19.1"),
      new Version("4.19.2"),
      new Version("4.19.3"),
      new Version("4.20.0"),
      new Version("4.21.0", new LiquibaseMigration("update/gitbucket-core_4.21.xml")),
      new Version("4.21.1"),
      new Version("4.21.2"),
      new Version("4.22.0", new LiquibaseMigration("update/gitbucket-core_4.22.xml")),
      new Version("4.23.0", new LiquibaseMigration("update/gitbucket-core_4.23.xml")),
      new Version("4.23.1"),
      new Version("4.24.0", new LiquibaseMigration("update/gitbucket-core_4.24.xml")),
      new Version("4.24.1"),
      new Version("4.25.0", new LiquibaseMigration("update/gitbucket-core_4.25.xml")),
      new Version("4.26.0"),
      new Version("4.27.0", new LiquibaseMigration("update/gitbucket-core_4.27.xml")),
      new Version("4.28.0"),
      new Version("4.29.0"),
      new Version("4.30.0"),
      new Version("4.30.1"),
      new Version("4.31.0", new LiquibaseMigration("update/gitbucket-core_4.31.xml")),
      new Version("4.31.1"),
      new Version("4.31.2"),
      new Version("4.32.0", new LiquibaseMigration("update/gitbucket-core_4.32.xml")),
      new Version("4.33.0"),
      new Version(
        "4.34.0",
        new Migration() {
          override def migrate(moduleId: String, version: String, context: java.util.Map[String, AnyRef]): Unit = {
            implicit val formats: Formats = Serialization.formats(NoTypeHints)
            import JDBCUtil._

            val conn = context.get(Solidbase.CONNECTION).asInstanceOf[Connection]
            val list = conn.select("SELECT * FROM ACTIVITY ORDER BY ACTIVITY_ID") { rs =>
              Activity(
                activityId = UUID.randomUUID().toString,
                userName = rs.getString("USER_NAME"),
                repositoryName = rs.getString("REPOSITORY_NAME"),
                activityUserName = rs.getString("ACTIVITY_USER_NAME"),
                activityType = rs.getString("ACTIVITY_TYPE"),
                message = rs.getString("MESSAGE"),
                additionalInfo = {
                  val additionalInfo = rs.getString("ADDITIONAL_INFO")
                  if (rs.wasNull()) None else Some(additionalInfo)
                },
                activityDate = rs.getTimestamp("ACTIVITY_DATE")
              )
            }
            Using.resource(new FileOutputStream(ActivityLog, true)) { out =>
              list.foreach { activity =>
                out.write((write(activity) + "\n").getBytes(StandardCharsets.UTF_8))
              }
            }
          }
        },
        new LiquibaseMigration("update/gitbucket-core_4.34.xml")
      ),
      new Version("4.35.0", new LiquibaseMigration("update/gitbucket-core_4.35.xml")),
      new Version("4.35.1"),
      new Version("4.35.2"),
      new Version("4.35.3"),
      new Version("4.36.0", new LiquibaseMigration("update/gitbucket-core_4.36.xml")),
      new Version("4.36.1"),
      new Version("4.36.2"),
      new Version("4.37.0", new LiquibaseMigration("update/gitbucket-core_4.37.xml")),
      new Version("4.37.1"),
      new Version("4.37.2"),
      new Version("4.38.0", new LiquibaseMigration("update/gitbucket-core_4.38.xml")),
      new Version("4.38.1"),
      new Version("4.38.2"),
      new Version("4.38.3"),
      new Version("4.38.4"),
      new Version("4.39.0", new LiquibaseMigration("update/gitbucket-core_4.39.xml")),
      new Version("4.40.0"),
      new Version("4.41.0"),
      new Version("4.42.0", new LiquibaseMigration("update/gitbucket-core_4.42.xml")),
      new Version("4.42.1"),
      new Version("4.43.0"),
      new Version("4.44.0", new LiquibaseMigration("update/gitbucket-core_4.44.xml")),
      new Version("4.45.0"),
      new Version("4.46.0", new LiquibaseMigration("update/gitbucket-core_4.46.xml")),
      new Version("4.46.1")
    )
    with PluginLifecycle {

  private val logger = LoggerFactory.getLogger(getClass)

  java.util.logging.Logger.getLogger("liquibase").setLevel(Level.SEVERE)

  override def onInitialize(context: ServletContext, settings: SystemSettings, conn: Connection): Unit = {
    logger.info("Plugin lifecycle: initializing via ServiceLoader")
    PluginRegistry.initialize(context, settings, conn)
  }

  override def onShutdown(context: ServletContext, settings: SystemSettings): Unit = {
    logger.info("Plugin lifecycle: shutting down")
    PluginRegistry.shutdown(context, settings)
  }

  override def onReload(context: ServletContext, settings: SystemSettings, conn: Connection): Unit = {
    logger.info("Plugin lifecycle: reloading changed plugins")
    PluginRegistry.reloadChangedPlugins(context, settings, conn)
  }
}
