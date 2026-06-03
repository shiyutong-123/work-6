package gitbucket.core

import java.sql.{Connection, DriverManager}
import io.github.gitbucket.solidbase.Solidbase
import liquibase.database.core.H2Database
import org.specs2.mutable.Specification
import org.specs2.mock.Mockito
import java.sql.SQLException

class GitBucketCoreModuleSpecs2Spec extends Specification with Mockito {

  "GitBucketCoreModule" should {
    "rollback to the previous valid state in VERSIONS table if LiquibaseMigration fails" in {
      // 1. Setup real in-memory H2 database
      val url = "jdbc:h2:mem:test_rollback;DB_CLOSE_DELAY=-1"
      val realConn = DriverManager.getConnection(url, "sa", "sa")
      
      // Initialize with a previous valid state (e.g., version 4.0.0)
      realConn.createStatement().execute("CREATE TABLE VERSIONS (MODULE_ID VARCHAR(100) NOT NULL PRIMARY KEY, VERSION VARCHAR(100) NOT NULL)")
      realConn.createStatement().execute("INSERT INTO VERSIONS (MODULE_ID, VERSION) VALUES ('gitbucket-core', '4.0.0')")
      
      // Enable transaction manually to mimic `Database() withTransaction`
      realConn.setAutoCommit(false)
      
      // 2. Wrap the connection in a dynamic proxy to simulate failure during migration
      import java.lang.reflect.{InvocationHandler, Method, Proxy}
      import java.sql.Statement

      val connectionHandler = new InvocationHandler {
        override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef = {
          if (method.getName.startsWith("createStatement")) {
            val realStmt = if (args == null) {
              method.invoke(realConn)
            } else {
              method.invoke(realConn, args: _*)
            }
            
            val statementHandler = new InvocationHandler {
              override def invoke(proxyStmt: Any, methodStmt: Method, argsStmt: Array[AnyRef]): AnyRef = {
                if (methodStmt.getName == "execute" || methodStmt.getName == "executeUpdate" || methodStmt.getName == "executeQuery") {
                  if (argsStmt != null && argsStmt.length > 0 && argsStmt(0).isInstanceOf[String]) {
                    val sql = argsStmt(0).asInstanceOf[String]
                    if (sql.toUpperCase.contains("ENABLE_ISSUES")) {
                      throw new SQLException("Simulated LiquibaseMigration failure")
                    }
                  }
                }
                if (argsStmt == null) {
                  methodStmt.invoke(realStmt)
                } else {
                  methodStmt.invoke(realStmt, argsStmt: _*)
                }
              }
            }
            
            Proxy.newProxyInstance(
              classOf[Statement].getClassLoader,
              Array(classOf[Statement]),
              statementHandler
            )
          } else if (method.getName.startsWith("prepareStatement")) {
            if (args != null && args.length > 0 && args(0).isInstanceOf[String]) {
              val sql = args(0).asInstanceOf[String]
              if (sql.toUpperCase.contains("ENABLE_ISSUES")) {
                throw new SQLException("Simulated LiquibaseMigration failure")
              }
            }
            val realStmt = if (args == null) {
              method.invoke(realConn)
            } else {
              method.invoke(realConn, args: _*)
            }
            // We could proxy PreparedStatement too, but just returning the real one is fine since we checked the SQL string above
            realStmt
          } else {
            if (args == null) {
              method.invoke(realConn)
            } else {
              method.invoke(realConn, args: _*)
            }
          }
        }
      }

      val spyConn = Proxy.newProxyInstance(
        classOf[Connection].getClassLoader,
        Array(classOf[Connection]),
        connectionHandler
      ).asInstanceOf[Connection]
      
      // 3. Run migration and expect failure
      val solidbase = new Solidbase()
      
      try {
        solidbase.migrate(
          spyConn,
          Thread.currentThread().getContextClassLoader(),
          new H2Database(),
          GitBucketCoreModule
        )
        ko("Migration should have failed")
      } catch {
        case e: Exception =>
          // 4. Rollback transaction
          spyConn.rollback()
          
          // Verify error message
          def hasExpectedMessage(ex: Throwable): Boolean = {
            if (ex == null) false
            else if (ex.getMessage != null && ex.getMessage.contains("Simulated LiquibaseMigration failure")) true
            else hasExpectedMessage(ex.getCause)
          }
          hasExpectedMessage(e) must beTrue
      }
      
      // 5. Verify system rolled back to the previous valid state (4.0.0)
      val rs = realConn.createStatement().executeQuery("SELECT VERSION FROM VERSIONS WHERE MODULE_ID = 'gitbucket-core'")
      rs.next() must beTrue
      val version = rs.getString("VERSION")
      version must_== "4.0.0"
      
      // Ensure no newer version was committed
      rs.next() must beFalse
      
      realConn.close()
      ok
    }
  }
}
