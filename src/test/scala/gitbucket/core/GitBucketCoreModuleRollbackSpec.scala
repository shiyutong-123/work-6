package gitbucket.core

import java.sql.{Connection, DriverManager}
import io.github.gitbucket.solidbase.Solidbase
import io.github.gitbucket.solidbase.manager.JDBCVersionManager
import io.github.gitbucket.solidbase.migration.Migration
import io.github.gitbucket.solidbase.model.{Module, Version}
import liquibase.database.core.H2Database
import org.specs2.mutable.Specification
import scala.util.Using

class GitBucketCoreModuleRollbackSpec extends Specification {

  sequential

  "GitBucketCoreModule migration rollback" should {

    "retain previous valid version in VERSIONS table when a custom Migration fails" in {
      Using.resource(createConnection()) { conn =>
        conn.setAutoCommit(false)

        val manager = new JDBCVersionManager(conn)
        manager.initialize()
        manager.updateVersion("test-module", "1.0.0")
        conn.commit()

        val initialVersion = manager.getCurrentVersion("test-module")
        initialVersion must beEqualTo("1.0.0")

        val failingModule = new Module(
          "test-module",
          new Version("1.0.0"),
          new Version("2.0.0", new Migration {
            override def migrate(moduleId: String, version: String, context: java.util.Map[String, AnyRef]): Unit = {
              throw new RuntimeException("Simulated migration failure at version 2.0.0")
            }
          }),
          new Version("3.0.0")
        )

        val solidbase = new Solidbase()
        val error = try {
          solidbase.migrate(conn, Thread.currentThread().getContextClassLoader, new H2Database(), failingModule)
          conn.commit()
          None
        } catch {
          case e: Throwable =>
            conn.rollback()
            Some(e)
        }

        error must beSome[Throwable]
        error.map(_.getMessage) must beSome(contain("Simulated migration failure at version 2.0.0"))

        val currentVersion = manager.getCurrentVersion("test-module")
        currentVersion must beEqualTo("1.0.0")
        currentVersion must not be equalTo("2.0.0")
        currentVersion must not be equalTo("3.0.0")
      }
    }

    "retain previous valid version when LiquibaseMigration fails due to missing resource" in {
      Using.resource(createConnection()) { conn =>
        conn.setAutoCommit(false)

        val manager = new JDBCVersionManager(conn)
        manager.initialize()
        manager.updateVersion("gitbucket-core-test", "4.0.0")
        conn.commit()

        val initialVersion = manager.getCurrentVersion("gitbucket-core-test")
        initialVersion must beEqualTo("4.0.0")

        val moduleWithBadMigration = new Module(
          "gitbucket-core-test",
          new Version("4.0.0"),
          new Version("4.1.0", new Migration {
            override def migrate(moduleId: String, version: String, context: java.util.Map[String, AnyRef]): Unit = {
              val conn = context.get(Solidbase.CONNECTION).asInstanceOf[Connection]
              conn.prepareStatement("THIS_IS_INVALID_SQL_THAT_WILL_FAIL").execute()
            }
          })
        )

        val solidbase = new Solidbase()
        val error = try {
          solidbase.migrate(
            conn,
            Thread.currentThread().getContextClassLoader,
            new H2Database(),
            moduleWithBadMigration
          )
          conn.commit()
          None
        } catch {
          case e: Throwable =>
            conn.rollback()
            Some(e)
        }

        error must beSome[Throwable]

        val currentVersion = manager.getCurrentVersion("gitbucket-core-test")
        currentVersion must beEqualTo("4.0.0")
      }
    }

    "update version successfully when all migrations succeed" in {
      Using.resource(createConnection()) { conn =>
        conn.setAutoCommit(false)

        val manager = new JDBCVersionManager(conn)
        manager.initialize()
        manager.updateVersion("test-module-success", "1.0.0")
        conn.commit()

        val successModule = new Module(
          "test-module-success",
          new Version("1.0.0"),
          new Version("2.0.0", new Migration {
            override def migrate(moduleId: String, version: String, context: java.util.Map[String, AnyRef]): Unit = {
              val conn = context.get(Solidbase.CONNECTION).asInstanceOf[Connection]
              conn.createStatement().execute("CREATE TABLE IF NOT EXISTS TEST_TABLE (ID INT PRIMARY KEY)")
            }
          }),
          new Version("3.0.0")
        )

        val solidbase = new Solidbase()
        val error = try {
          solidbase.migrate(
            conn,
            Thread.currentThread().getContextClassLoader,
            new H2Database(),
            successModule
          )
          conn.commit()
          None
        } catch {
          case e: Throwable =>
            conn.rollback()
            Some(e)
        }

        error must beNone

        val currentVersion = manager.getCurrentVersion("test-module-success")
        currentVersion must beEqualTo("3.0.0")

        val rs = conn.createStatement().executeQuery(
          "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'TEST_TABLE'"
        )
        rs.next()
        rs.getInt(1) must beEqualTo(1)
        rs.close()
      }
    }

    "verify VERSIONS table schema integrity after failed migration" in {
      Using.resource(createConnection()) { conn =>
        conn.setAutoCommit(false)

        val manager = new JDBCVersionManager(conn)
        manager.initialize()
        manager.updateVersion("test-module-integrity", "1.0.0")
        conn.commit()

        val failingModule = new Module(
          "test-module-integrity",
          new Version("1.0.0"),
          new Version("2.0.0", new Migration {
            override def migrate(moduleId: String, version: String, context: java.util.Map[String, AnyRef]): Unit = {
              throw new RuntimeException("Deliberate failure for integrity check")
            }
          })
        )

        val solidbase = new Solidbase()
        try {
          solidbase.migrate(
            conn,
            Thread.currentThread().getContextClassLoader,
            new H2Database(),
            failingModule
          )
          conn.commit()
        } catch {
          case _: Throwable => conn.rollback()
        }

        val rs = conn.createStatement().executeQuery(
          "SELECT MODULE_ID, VERSION FROM VERSIONS WHERE MODULE_ID = 'test-module-integrity'"
        )
        val versions = Iterator.continually((rs.next(), rs)).takeWhile(_._1).map { case (_, r) =>
          (r.getString("MODULE_ID"), r.getString("VERSION"))
        }.toList
        rs.close()

        versions must haveSize(1)
        versions must contain(("test-module-integrity", "1.0.0"))

        val rs2 = conn.getMetaData.getColumns(null, null, "VERSIONS", null)
        val columns = Iterator.continually((rs2.next(), rs2)).takeWhile(_._1).map { case (_, r) =>
          (r.getString("COLUMN_NAME"), r.getInt("DATA_TYPE"))
        }.toList
        rs2.close()

        columns.map(_._1) must contain(allOf("MODULE_ID", "VERSION"))
      }
    }
  }

  private def createConnection(): Connection = {
    DriverManager.getConnection("jdbc:h2:mem:rollback_test;DB_CLOSE_DELAY=-1", "sa", "sa")
  }
}