package gitbucket.core

import java.sql.{Connection, DriverManager}

import io.github.gitbucket.solidbase.Solidbase
import io.github.gitbucket.solidbase.manager.VersionManager
import io.github.gitbucket.solidbase.migration.{LiquibaseMigration, Migration}
import io.github.gitbucket.solidbase.model.{Module, Version}
import liquibase.database.core.H2Database
import org.specs2.mutable.Specification
import org.specs2.specification.Scope

class GitBucketCoreModuleRollbackSpec extends Specification {

  trait TestScope extends Scope {
    // 创建一个内存H2数据库
    val conn: Connection = DriverManager.getConnection("jdbc:h2:mem:testrollback;DB_CLOSE_DELAY=-1", "sa", "sa")
    val classLoader: ClassLoader = Thread.currentThread().getContextClassLoader
    val database: H2Database = new H2Database()

    def closeConnection(): Unit = {
      if (!conn.isClosed) {
        conn.close()
      }
    }
  }

  "GitBucketCoreModule rollback mechanism" should {

    "maintain previous valid version in VERSIONS table when a migration fails" in new TestScope {
      try {
        // 创建一个简单的测试模块，其中第二个版本的迁移会失败
        val failingMigration = new Migration() {
          override def migrate(moduleId: String, version: String, context: java.util.Map[String, Object]): Unit = {
            throw new RuntimeException("Intentional migration failure for testing")
          }
        }

        val testModule = new Module(
          "test-module",
          new Version("1.0.0", new LiquibaseMigration("update/gitbucket-core_4.0.xml")),
          new Version("1.1.0", failingMigration)
        )

        val solidbase = new Solidbase()
        var versionAfterFailure: String = null

        // 先成功执行到 1.0.0 版本
        solidbase.migrate(
          conn,
          classLoader,
          database,
          new Module("test-module", new Version("1.0.0", new LiquibaseMigration("update/gitbucket-core_4.0.xml")))
        )

        // 获取当前版本，应该是 1.0.0
        val stmt = conn.createStatement()
        val rs = stmt.executeQuery("SELECT VERSION FROM VERSIONS WHERE MODULE_ID = 'test-module'")
        val initialVersion = if (rs.next()) rs.getString("VERSION") else null
        rs.close()
        stmt.close()

        initialVersion must_== "1.0.0"

        // 现在尝试执行包含失败迁移的完整模块
        try {
          solidbase.migrate(conn, classLoader, database, testModule)
          failure("Migration should have failed but didn't")
        } catch {
          case _: RuntimeException => // expected
        }

        // 再次检查 VERSIONS 表中的版本，应该仍然是 1.0.0
        val stmt2 = conn.createStatement()
        val rs2 = stmt2.executeQuery("SELECT VERSION FROM VERSIONS WHERE MODULE_ID = 'test-module'")
        versionAfterFailure = if (rs2.next()) rs2.getString("VERSION") else null
        rs2.close()
        stmt2.close()

        versionAfterFailure must_== "1.0.0"
      } finally {
        closeConnection()
      }
    }

    "handle multiple migrations in a single version correctly when one fails" in new TestScope {
      try {
        val successMigration1 = new Migration() {
          var executed = false
          override def migrate(moduleId: String, version: String, context: java.util.Map[String, Object]): Unit = {
            executed = true
          }
        }

        val failingMigration = new Migration() {
          override def migrate(moduleId: String, version: String, context: java.util.Map[String, Object]): Unit = {
            throw new RuntimeException("Intentional failure in second migration")
          }
        }

        val testModule = new Module(
          "test-module2",
          new Version("1.0.0"),
          new Version("1.1.0", successMigration1, failingMigration)
        )

        val solidbase = new Solidbase()

        // 先初始化到 1.0.0
        solidbase.migrate(
          conn,
          classLoader,
          database,
          new Module("test-module2", new Version("1.0.0"))
        )

        // 尝试执行 1.1.0 版本
        try {
          solidbase.migrate(conn, classLoader, database, testModule)
          failure("Migration should have failed but didn't")
        } catch {
          case _: RuntimeException => // expected
        }

        // 检查版本是否仍然是 1.0.0
        val stmt = conn.createStatement()
        val rs = stmt.executeQuery("SELECT VERSION FROM VERSIONS WHERE MODULE_ID = 'test-module2'")
        val currentVersion = if (rs.next()) rs.getString("VERSION") else null
        rs.close()
        stmt.close()

        currentVersion must_== "1.0.0"
        successMigration1.executed must_== true // 即使第一个迁移执行成功了，版本也没有更新
      } finally {
        closeConnection()
      }
    }

    "not update VERSIONS table until all migrations in a version are successful" in new TestScope {
      try {
        var migration1Executed = false
        var migration2Executed = false

        val migration1 = new Migration() {
          override def migrate(moduleId: String, version: String, context: java.util.Map[String, Object]): Unit = {
            migration1Executed = true
          }
        }

        val migration2 = new Migration() {
          override def migrate(moduleId: String, version: String, context: java.util.Map[String, Object]): Unit = {
            migration2Executed = true
          }
        }

        val testModule = new Module(
          "test-module3",
          new Version("1.0.0"),
          new Version("1.1.0", migration1, migration2)
        )

        val solidbase = new Solidbase()

        // 先初始化到 1.0.0
        solidbase.migrate(
          conn,
          classLoader,
          database,
          new Module("test-module3", new Version("1.0.0"))
        )

        // 检查初始版本
        var stmt = conn.createStatement()
        var rs = stmt.executeQuery("SELECT VERSION FROM VERSIONS WHERE MODULE_ID = 'test-module3'")
        var initialVersion = if (rs.next()) rs.getString("VERSION") else null
        rs.close()
        stmt.close()

        initialVersion must_== "1.0.0"

        // 执行完整迁移
        solidbase.migrate(conn, classLoader, database, testModule)

        // 检查版本是否已更新到 1.1.0
        stmt = conn.createStatement()
        rs = stmt.executeQuery("SELECT VERSION FROM VERSIONS WHERE MODULE_ID = 'test-module3'")
        val finalVersion = if (rs.next()) rs.getString("VERSION") else null
        rs.close()
        stmt.close()

        finalVersion must_== "1.1.0"
        migration1Executed must_== true
        migration2Executed must_== true
      } finally {
        closeConnection()
      }
    }
  }
}
