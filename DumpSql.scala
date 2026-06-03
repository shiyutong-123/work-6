import java.sql._
import io.github.gitbucket.solidbase.Solidbase
import io.github.gitbucket.solidbase.model.Module
import liquibase.database.core.H2Database
import gitbucket.core.GitBucketCoreModule

object DumpSql extends App {
  val conn = DriverManager.getConnection("jdbc:h2:mem:test;TRACE_LEVEL_SYSTEM_OUT=2", "sa", "sa")
  try {
    new Solidbase().migrate(
      conn,
      Thread.currentThread().getContextClassLoader(),
      new H2Database(),
      new Module(GitBucketCoreModule.getModuleId, GitBucketCoreModule.getVersions)
    )
  } catch {
    case e: Exception => e.printStackTrace()
  }
}
