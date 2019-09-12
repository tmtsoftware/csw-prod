package csw.database

import java.util.Properties
import java.util.concurrent.CompletableFuture

import akka.actor.typed.ActorSystem
import com.typesafe.config.{Config, ConfigFactory}
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import csw.database.commons.{DatabaseLogger, DatabaseServiceLocationResolver}
import csw.database.exceptions.DatabaseException
import csw.location.api.javadsl.ILocationService
import csw.location.api.scaladsl.LocationService
import csw.logging.api.scaladsl.Logger
import org.jooq.impl.DSL
import org.jooq.{DSLContext, SQLDialect}

import scala.async.Async.{async, await}
import scala.jdk.CollectionConverters._
import scala.compat.java8.FutureConverters.FutureOps
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

private[database] object DatabaseServiceFactory {
  val ReadUsernameHolder = "DB_READ_USERNAME"
  val ReadPasswordHolder = "DB_READ_PASSWORD"
}

/**
 * DatabaseServiceFactory provides a mechanism to connect to the database server and get the handle of Jooq's DSLContext.
 * To know in detail about Jooq please refer [[https://www.jooq.org/learn/]].
 *
 * @param actorSystem component's actor system, used for
 *                    1) reading the database configurations and
 *                    2) schedule the task of database connection
 * @param values used for testing purposes, to manually set the values for credentials instead of reading from env vars
 */
class DatabaseServiceFactory private[database] (actorSystem: ActorSystem[_], values: Map[String, String]) {

  /**
   * Creates the DatabaseServiceFactory. It is not injected in `CswContext` like other csw services.
   * Instead developers are expected to create an instance of DatabaseServiceFactory and then use it.
   *
   * @param actorSystem component's actor system, used for
   *                    1) reading the database configurations and
   *                    2) schedule the task of database connection
   * @return DatabaseServiceFactory
   */
  def this(actorSystem: ActorSystem[_]) = this(actorSystem, Map.empty)

  private val log: Logger    = DatabaseLogger.getLogger
  private val config: Config = actorSystem.settings.config

  private implicit val ec: ExecutionContext = actorSystem.executionContext

  import DatabaseServiceFactory._

  /**
   * Creates connection to database with default read access. The username and password for read access is picked from
   * environment variables set on individual's machine i.e. `DB_READ_USERNAME` and `DB_READ_PASSWORD`. It is expected that
   * developers set these variables before calling this method.
   *
   * @param locationService used to locate the database server
   * @param dbName used to connect to the database
   * @return a Future that completes with Jooq's `DSLContext` or fails with [[csw.database.exceptions.DatabaseException]].
   *         DSLContext provide methods like `fetchAsync`, `executeAsync`, `executeBatch`, etc. Moreover, see
   *         [[csw.database.javadsl.JooqHelper]] for java and [[csw.database.scaladsl.JooqExtentions]] for
   *         scala which provides wrapper methods on Jooq's `DSLContext`.
   */
  def makeDsl(locationService: LocationService, dbName: String): Future[DSLContext] =
    makeDsl(locationService, dbName, ReadUsernameHolder, ReadPasswordHolder)

  /**
   * Creates connection to database with credentials picked from environment variables. Names of these environment variables
   * is expected as method parameters and developers are expected to set these variables before calling this method.
   *
   * @param locationService used to locate the database server
   * @param dbName used to connect to the database
   * @param usernameHolder name of env variable from which the username will be read
   * @param passwordHolder name of env variable from which the password will be read
   * @return a Future that completes with Jooq's `DSLContext` or fails with [[csw.database.exceptions.DatabaseException]].
   *         DSLContext provide methods like `fetchAsync`, `executeAsync`, `executeBatch`, etc. Moreover, see
   *         [[csw.database.javadsl.JooqHelper]] for java and [[csw.database.scaladsl.JooqExtentions]] for
   *         scala which provides wrapper methods on Jooq's `DSLContext`.
   */
  def makeDsl(
      locationService: LocationService,
      dbName: String,
      usernameHolder: String,
      passwordHolder: String
  ): Future[DSLContext] = async {
    val resolver = new DatabaseServiceLocationResolver(locationService)
    val uri      = await(resolver.uri())
    val envVars  = sys.env ++ values
    val dataSource: Map[String, Any] = Map(
      "dataSource.serverName"   -> uri.getHost,
      "dataSource.portNumber"   -> uri.getPort,
      "dataSource.databaseName" -> dbName,
      "dataSource.user"         -> envVars(usernameHolder), //NoSuchElementFoundException can be thrown if no env variable is set
      "dataSource.password"     -> envVars(passwordHolder) //NoSuchElementFoundException can be thrown if no env variable is set
    )
    val dataSourceConfig = ConfigFactory.parseMap(dataSource.asJava)
    createDslInternal(Some(dataSourceConfig))
  }

  /**
   * A java method to create connection to database with default read access. The username and password for read access
   * is picked from environment variables set on individual's machine i.e. `DB_READ_USERNAME` and `DB_READ_PASSWORD`. It is
   * expected that developers set these variables before calling this method.
   *
   * @param locationService used to locate the database server
   * @param dbName used to connect to the database
   * @return a CompletableFuture that completes with Jooq's `DSLContext` or fails with [[csw.database.exceptions.DatabaseException]].
   *         DSLContext provide methods like `fetchAsync`, `executeAsync`, `executeBatch`, etc. Moreover, see
   *         [[csw.database.javadsl.JooqHelper]] for java and [[csw.database.scaladsl.JooqExtentions]] for
   *         scala which provides wrapper methods on Jooq's `DSLContext`.
   */
  def jMakeDsl(locationService: ILocationService, dbName: String): CompletableFuture[DSLContext] =
    makeDsl(locationService.asScala, dbName).toJava.toCompletableFuture

  /**
   * A java method to create the connection to database with credentials picked from environment variables. Names of these
   * environment variables is expected as method parameters and developers are expected to set these variables before
   * calling this method.
   *
   * @param locationService used to locate the database server
   * @param dbName used to connect to the database
   * @param usernameHolder name of env variable from which the username will be read
   * @param passwordHolder name of env variable from which the password will be read
   * @return a CompletableFuture that completes with Jooq's `DSLContext` or fails with [[csw.database.exceptions.DatabaseException]].
   *         DSLContext provide methods like `fetchAsync`, `executeAsync`, `executeBatch`, etc. Moreover, see
   *         [[csw.database.javadsl.JooqHelper]] for java and [[csw.database.scaladsl.JooqExtentions]] for
   *         scala which provides wrapper methods on Jooq's `DSLContext`.
   */
  def jMakeDsl(
      locationService: ILocationService,
      dbName: String,
      usernameHolder: String,
      passwordHolder: String
  ): CompletableFuture[DSLContext] =
    makeDsl(locationService.asScala, dbName, usernameHolder, passwordHolder).toJava.toCompletableFuture

  /**
   * Creates a connection to database using the configuration read from application.conf. Refer the reference.conf/database
   * documentation to know how to provide database connection properties.
   *
   * @note This method is strongly recommended to use for testing purposes only
   * @return a Future that completes with Jooq's `DSLContext` or fails with [[csw.database.exceptions.DatabaseException]].
   *         DSLContext provide methods like `fetchAsync`, `executeAsync`, `executeBatch`, etc. Moreover, see
   *         [[csw.database.javadsl.JooqHelper]] for java and [[csw.database.scaladsl.JooqExtentions]] for
   *         scala which provides wrapper methods on Jooq's `DSLContext`.
   */
  def makeDsl(): Future[DSLContext] = Future(createDslInternal())

  /**
   * A java method to Creates a connection to database using the configuration read from application.conf. Refer the
   * reference.conf/database documentation to know how to provide database connection properties.
   *
   * @note This method is strongly recommended to use for testing purposes only
   * @return a CompletableFuture that completes with Jooq's `DSLContext` or fails with [[csw.database.exceptions.DatabaseException]].
   *         DSLContext provide methods like `fetchAsync`, `executeAsync`, `executeBatch`, etc. Moreover, see
   *         [[csw.database.javadsl.JooqHelper]] for java and [[csw.database.scaladsl.JooqExtentions]] for
   *         scala which provides wrapper methods on Jooq's `DSLContext`.
   */
  def jMakeDsl(): CompletableFuture[DSLContext] = makeDsl().toJava.toCompletableFuture

  private[database] def makeDsl(port: Int): Future[DSLContext] = {
    val config = ConfigFactory.parseString(s"dataSource.portNumber = $port")
    Future(createDslInternal(Some(config)))
  }

  /************ INTERNAL ************/
  private def createDslInternal(maybeConfig: Option[Config] = None): DSLContext = {
    val cswDatabase: Config      = config.getConfig("csw-database")
    val dataSourceConfig: Config = cswDatabase.getConfig("hikari-datasource")

    val finalDataSourceConfig = maybeConfig match {
      case None               => dataSourceConfig
      case Some(customConfig) => customConfig.withFallback(dataSourceConfig)
    }

    try {
      val hikariConfig = new HikariConfig(toProperties(finalDataSourceConfig))
      log.info(s"Connecting to database using config :[$finalDataSourceConfig]")
      DSL.using(new HikariDataSource(hikariConfig), SQLDialect.POSTGRES)
    } catch {
      case NonFatal(ex) =>
        val exception = DatabaseException(ex.getMessage, ex.getCause)
        log.error(exception.getMessage, ex = exception)
        throw exception
    }
  }

  private def toProperties(config: Config): Properties = {
    val properties = new Properties()
    config.entrySet.forEach { e =>
      properties.setProperty(e.getKey, config.getString(e.getKey))
    }
    properties
  }
}
