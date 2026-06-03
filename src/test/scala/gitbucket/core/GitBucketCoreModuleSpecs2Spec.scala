package gitbucket.core

import java.sql.{Connection, DriverManager, Statement}
import io.github.gitbucket.solidbase.Solidbase
import io.github.gitbucket.solidbase.model.{Module, Version}
import io.github.gitbucket.solidbase.migration.{LiquibaseMigration, Migration}
import liquibase.database.core.H2Database
import liquibase.database.jvm.JdbcConnection
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try, Using}

@RunWith(classOf[JUnitRunner])
class GitBucketCoreModuleSpecs2Spec extends Specification {

  "GitBucketCoreModule" should {

    "successfully rollback to previous version when LiquibaseMigration fails" in {
      val conn = DriverManager.getConnection("jdbc:h2:mem:rollback_test;DB_CLOSE_DELAY=-1", "sa", "sa")
      
      try {
        val solidbase = new Solidbase()
        val h2Database = new H2Database()
        h2Database.setConnection(new JdbcConnection(conn))
        
        val testModule = new Module("test-module",
          new Version("1.0.0",
            new LiquibaseMigration("update/gitbucket-core_4.0.xml")
          ),
          new Version("1.1.0",
            new Migration() {
              override def migrate(moduleId: String, version: String, context: java.util.Map[String, AnyRef]): Unit = {
                val stmt = context.get(Solidbase.CONNECTION).asInstanceOf[Connection].createStatement()
                try {
                  stmt.execute("CREATE TABLE TEST_TABLE (ID INT PRIMARY KEY, NAME VARCHAR(100))")
                } finally {
                  stmt.close()
                }
              }
            }
          ),
          new Version("1.2.0",
            new FailingLiquibaseMigration("update/gitbucket-core_4.2.xml")
          )
        )
        
        solidbase.migrate(conn, Thread.currentThread().getContextClassLoader(), h2Database, testModule)
        
        val versionAfterSuccess = getCurrentVersion(conn, "test-module")
        versionAfterSuccess must beSome("1.1.0")
        
        Try {
          solidbase.migrate(conn, Thread.currentThread().getContextClassLoader(), h2Database, testModule)
        } match {
          case Success(_) => 
            ko("Migration should have failed but succeeded")
          case Failure(exception) =>
            exception.getMessage must contain("Simulated migration failure")
            
            val versionAfterFailure = getCurrentVersion(conn, "test-module")
            versionAfterFailure must beSome("1.1.0")
            
            val tableExists = checkTableExists(conn, "TEST_TABLE")
            tableExists must beTrue
            
            val failingTableExists = checkTableExists(conn, "FAILING_TABLE")
            failingTableExists must beFalse
        }
      } finally {
        conn.close()
      }
    }

    "maintain VERSIONS table consistency during migration failure" in {
      val conn = DriverManager.getConnection("jdbc:h2:mem:versions_test;DB_CLOSE_DELAY=-1", "sa", "sa")
      
      try {
        val solidbase = new Solidbase()
        val h2Database = new H2Database()
        h2Database.setConnection(new JdbcConnection(conn))
        
        val testModule = new Module("versions-test",
          new Version("2.0.0",
            new Migration() {
              override def migrate(moduleId: String, version: String, context: java.util.Map[String, AnyRef]): Unit = {
                val stmt = context.get(Solidbase.CONNECTION).asInstanceOf[Connection].createStatement()
                try {
                  stmt.execute("CREATE TABLE INITIAL_TABLE (ID INT PRIMARY KEY)")
                } finally {
                  stmt.close()
                }
              }
            }
          ),
          new Version("2.1.0",
            new FailingLiquibaseMigration("update/gitbucket-core_4.6.xml")
          )
        )
        
        solidbase.migrate(conn, Thread.currentThread().getContextClassLoader(), h2Database, testModule)
        
        val initialVersion = getCurrentVersion(conn, "versions-test")
        initialVersion must beSome("2.0.0")
        
        val migrationResult = Try {
          solidbase.migrate(conn, Thread.currentThread().getContextClassLoader(), h2Database, testModule)
        }
        
        migrationResult must beAFailedTry
        
        val versionAfterFailedMigration = getCurrentVersion(conn, "versions-test")
        versionAfterFailedMigration must beSome("2.0.0")
        
        val initialTableExists = checkTableExists(conn, "INITIAL_TABLE")
        initialTableExists must beTrue
      } finally {
        conn.close()
      }
    }

    "handle multiple successful migrations before failure" in {
      val conn = DriverManager.getConnection("jdbc:h2:mem:multi_test;DB_CLOSE_DELAY=-1", "sa", "sa")
      
      try {
        val solidbase = new Solidbase()
        val h2Database = new H2Database()
        h2Database.setConnection(new JdbcConnection(conn))
        
        val testModule = new Module("multi-test",
          new Version("3.0.0",
            new Migration() {
              override def migrate(moduleId: String, version: String, context: java.util.Map[String, AnyRef]): Unit = {
                val stmt = context.get(Solidbase.CONNECTION).asInstanceOf[Connection].createStatement()
                try {
                  stmt.execute("CREATE TABLE STEP_ONE (ID INT PRIMARY KEY)")
                } finally {
                  stmt.close()
                }
              }
            }
          ),
          new Version("3.1.0",
            new Migration() {
              override def migrate(moduleId: String, version: String, context: java.util.Map[String, AnyRef]): Unit = {
                val stmt = context.get(Solidbase.CONNECTION).asInstanceOf[Connection].createStatement()
                try {
                  stmt.execute("CREATE TABLE STEP_TWO (ID INT PRIMARY KEY)")
                } finally {
                  stmt.close()
                }
              }
            }
          ),
          new Version("3.2.0",
            new FailingLiquibaseMigration("update/gitbucket-core_4.7.xml")
          )
        )
        
        solidbase.migrate(conn, Thread.currentThread().getContextClassLoader(), h2Database, testModule)
        
        val versionAfterFirst = getCurrentVersion(conn, "multi-test")
        versionAfterFirst must beSome("3.0.0")
        
        solidbase.migrate(conn, Thread.currentThread().getContextClassLoader(), h2Database, testModule)
        
        val versionAfterSecond = getCurrentVersion(conn, "multi-test")
        versionAfterSecond must beSome("3.1.0")
        
        val failedMigrationResult = Try {
          solidbase.migrate(conn, Thread.currentThread().getContextClassLoader(), h2Database, testModule)
        }
        
        failedMigrationResult must beAFailedTry
        
        val finalVersion = getCurrentVersion(conn, "multi-test")
        finalVersion must beSome("3.1.0")
        
        val stepOneExists = checkTableExists(conn, "STEP_ONE")
        stepOneExists must beTrue
        
        val stepTwoExists = checkTableExists(conn, "STEP_TWO")
        stepTwoExists must beTrue
      } finally {
        conn.close()
      }
    }

    "correctly read VERSIONS table after rollback" in {
      val conn = DriverManager.getConnection("jdbc:h2:mem:read_versions_test;DB_CLOSE_DELAY=-1", "sa", "sa")
      
      try {
        val solidbase = new Solidbase()
        val h2Database = new H2Database()
        h2Database.setConnection(new JdbcConnection(conn))
        
        val testModule = new Module("read-versions-test",
          new Version("4.0.0",
            new Migration() {
              override def migrate(moduleId: String, version: String, context: java.util.Map[String, AnyRef]): Unit = {
                val stmt = context.get(Solidbase.CONNECTION).asInstanceOf[Connection].createStatement()
                try {
                  stmt.execute("CREATE TABLE VERSIONED_TABLE (ID INT PRIMARY KEY, VERSION_DATA VARCHAR(50))")
                  stmt.execute("INSERT INTO VERSIONED_TABLE VALUES (1, 'initial')")
                } finally {
                  stmt.close()
                }
              }
            }
          ),
          new Version("4.1.0",
            new FailingLiquibaseMigration("update/gitbucket-core_4.9.xml")
          )
        )
        
        solidbase.migrate(conn, Thread.currentThread().getContextClassLoader(), h2Database, testModule)
        
        val initialVersion = getCurrentVersion(conn, "read-versions-test")
        initialVersion must beSome("4.0.0")
        
        Try {
          solidbase.migrate(conn, Thread.currentThread().getContextClassLoader(), h2Database, testModule)
        }
        
        val versionAfterFailure = getCurrentVersion(conn, "read-versions-test")
        versionAfterFailure must beSome("4.0.0")
        
        val dataIntact = checkDataIntegrity(conn)
        dataIntact must beTrue
      } finally {
        conn.close()
      }
    }
  }

  private def getCurrentVersion(conn: Connection, moduleId: String): Option[String] = {
    Using.resource(conn.prepareStatement("SELECT VERSION FROM VERSIONS WHERE MODULE_ID = ?")) { stmt =>
      stmt.setString(1, moduleId)
      Using.resource(stmt.executeQuery()) { rs =>
        if (rs.next()) Some(rs.getString("VERSION")) else None
      }
    }
  }

  private def checkTableExists(conn: Connection, tableName: String): Boolean = {
    Using.resource(conn.getMetaData.getTables(null, null, tableName.toUpperCase, null)) { rs =>
      rs.next()
    }
  }

  private def checkDataIntegrity(conn: Connection): Boolean = {
    Using.resource(conn.prepareStatement("SELECT VERSION_DATA FROM VERSIONED_TABLE WHERE ID = 1")) { stmt =>
      Using.resource(stmt.executeQuery()) { rs =>
        if (rs.next()) {
          rs.getString("VERSION_DATA") == "initial"
        } else false
      }
    }
  }
}

class FailingLiquibaseMigration(resourcePath: String) extends LiquibaseMigration(resourcePath) {
  override def migrate(moduleId: String, version: String, context: java.util.Map[String, AnyRef]): Unit = {
    throw new RuntimeException(s"Simulated migration failure for version ${version}")
  }
}
