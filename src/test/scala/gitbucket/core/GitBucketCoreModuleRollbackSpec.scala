package gitbucket.core

import io.github.gitbucket.solidbase.Solidbase
import io.github.gitbucket.solidbase.migration.LiquibaseMigration
import io.github.gitbucket.solidbase.model.{Module, Version}
import liquibase.database.core.H2Database
import org.mockito.ArgumentMatchers.anyString
import org.mockito.invocation.InvocationOnMock
import org.mockito.Mockito.{doAnswer, spy}
import org.specs2.mutable.Specification

import java.sql.{Connection, DriverManager}
import scala.collection.mutable.ListBuffer
import scala.util.{Try, Using}

class GitBucketCoreModuleRollbackSpec extends Specification {

  sequential

  "GitBucketCoreModule" should {
    "keep the previous valid version in VERSIONS when LiquibaseMigration fails" in {
      org.h2.Driver.load()

      val jdbcUrl = s"jdbc:h2:mem:gitbucket-core-rollback-${System.nanoTime()};DB_CLOSE_DELAY=-1"

      withMockedConnection(jdbcUrl) { (conn, executedSql) =>
        initializeVersionsTable(conn, GitBucketCoreModule.getModuleId, "4.46.0")

        val initialStateCheck = currentVersion(conn, GitBucketCoreModule.getModuleId) must beSome("4.46.0")

        executedSql.clear()

        val failure = captureFailure {
          new Solidbase().migrate(
            conn,
            Thread.currentThread().getContextClassLoader,
            new H2Database(),
            new Module(
              GitBucketCoreModule.getModuleId,
              new Version("4.46.0"),
              new Version("4.46.1", failingLiquibaseMigration)
            )
          )
        }

        val migrationSql = executedSql.toList

        val failureCheck = failure must beSome.like {
          case ex: IllegalStateException => ex.getMessage must contain("simulated LiquibaseMigration failure")
        }
        val finalStateCheck = currentVersion(conn, GitBucketCoreModule.getModuleId) must beSome("4.46.0")
        val rowCountCheck = versionRowCount(conn, GitBucketCoreModule.getModuleId) must beEqualTo(1)
        val sqlCheck = migrationSql must beEqualTo(List("SELECT VERSION FROM VERSIONS WHERE MODULE_ID = ?"))

        initialStateCheck and failureCheck and finalStateCheck and rowCountCheck and sqlCheck
      }
    }
  }

  private def withMockedConnection[A](jdbcUrl: String)(f: (Connection, ListBuffer[String]) => A): A = {
    val rawConnection = DriverManager.getConnection(jdbcUrl, "sa", "sa")
    val executedSql = ListBuffer.empty[String]
    val connection = spy(rawConnection)

    doAnswer((invocation: InvocationOnMock) => {
      executedSql += invocation.getArgument[String](0)
      invocation.callRealMethod()
    }).when(connection).prepareStatement(anyString())

    try {
      f(connection, executedSql)
    } finally {
      connection.close()
    }
  }

  private def initializeVersionsTable(conn: Connection, moduleId: String, version: String): Unit = {
    Using.resource(conn.createStatement()) { stmt =>
      stmt.executeUpdate("CREATE TABLE VERSIONS (MODULE_ID VARCHAR(100) NOT NULL PRIMARY KEY, VERSION VARCHAR(100) NOT NULL)")
    }

    Using.resource(conn.prepareStatement("INSERT INTO VERSIONS (MODULE_ID, VERSION) VALUES (?, ?)")) { stmt =>
      stmt.setString(1, moduleId)
      stmt.setString(2, version)
      stmt.executeUpdate()
    }
  }

  private def currentVersion(conn: Connection, moduleId: String): Option[String] = {
    Using.resource(conn.prepareStatement("SELECT VERSION FROM VERSIONS WHERE MODULE_ID = ?")) { stmt =>
      stmt.setString(1, moduleId)
      Using.resource(stmt.executeQuery()) { rs =>
        if (rs.next()) Some(rs.getString(1)) else None
      }
    }
  }

  private def versionRowCount(conn: Connection, moduleId: String): Int = {
    Using.resource(conn.prepareStatement("SELECT COUNT(*) FROM VERSIONS WHERE MODULE_ID = ?")) { stmt =>
      stmt.setString(1, moduleId)
      Using.resource(stmt.executeQuery()) { rs =>
        rs.next()
        rs.getInt(1)
      }
    }
  }

  private def captureFailure(action: => Unit): Option[Throwable] = {
    Try(action).failed.toOption
  }

  private def failingLiquibaseMigration: LiquibaseMigration = new LiquibaseMigration {
    override def migrate(moduleId: String, version: String, context: java.util.Map[String, Object]): Unit = {
      throw new IllegalStateException("simulated LiquibaseMigration failure")
    }
  }
}
