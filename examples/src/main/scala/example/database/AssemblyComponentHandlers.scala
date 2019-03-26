package example.database

import akka.actor.typed.scaladsl.ActorContext
import csw.command.client.messages.TopLevelActorMessage
import csw.database.DatabaseServiceFactory
import csw.framework.models.CswContext
import csw.framework.scaladsl.ComponentHandlers
import csw.location.api.models.TrackingEvent
import csw.params.commands.CommandResponse.Completed
import csw.params.commands.{CommandResponse, ControlCommand}
import csw.params.core.models.Id
import org.jooq.{DSLContext, Query}

import scala.async.Async.async
import scala.concurrent.{ExecutionContextExecutor, Future}

//DEOPSCSW-615: DB service accessible to CSW component developers
class AssemblyComponentHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext)
    extends ComponentHandlers(ctx, cswCtx) {

  import cswCtx._
  implicit val ec: ExecutionContextExecutor = ctx.executionContext

  private var dsl: DSLContext = _
  override def initialize(): Future[Unit] = async {
    //#dbFactory-access
    val dbFactory = new DatabaseServiceFactory(ctx.system)

    dbFactory
      .makeDsl(locationService, "postgres") // postgres is dbName
      .foreach((dsl: DSLContext) ⇒ this.dsl = dsl) // save returned dsl to a local variable
    //#dbFactory-access

    //#dbFactory-write-access
    dbFactory
      .makeDsl(locationService, "postgres", "DB_WRITE_USERNAME", "DB_WRITE_PASSWORD")
      .foreach((dsl: DSLContext) ⇒ this.dsl = dsl) // save returned dsl to a local variable
    //#dbFactory-write-access

    //#dbFactory-test-access
    dbFactory
      .makeDsl()
      .foreach((dsl: DSLContext) ⇒ this.dsl = dsl) // save returned dsl to a local variable
    //#dbFactory-test-access
  }

  override def onSubmit(runId: Id, controlCommand: ControlCommand): CommandResponse.SubmitResponse = {

    //#dsl-create
    val createQuery: Query = dsl.query("CREATE TABLE films (id SERIAL PRIMARY KEY, Name VARCHAR (10) NOT NULL)")

    import csw.database.scaladsl.JooqExtentions.RichQuery
    val createResultF: Future[Integer] = createQuery.executeAsyncScala()
    createResultF.foreach(result ⇒ println(s"Films table created with $result"))
    //#dsl-create

    //#dsl-batch
    val movie_2 = "movie_2"

    val queries = dsl.queries(
      dsl.query("INSERT INTO films(name) VALUES (?)", "movie_1"),
      dsl.query("INSERT INTO films(id, name) VALUES (?, ?)", "2", movie_2)
    )

    import csw.database.scaladsl.JooqExtentions.RichQueries
    val batchResultF: Future[List[Int]] = queries.executeBatchAsync()
    batchResultF.foreach(results ⇒ println(s"executed queries [$queries] with results [$results]"))
    //#dsl-batch

    //#dsl-fetch
    // domain model
    case class Films(id: Int, name: String) // variable name and type should be same as column's name and type in database

    // fetch data from table and map it to Films class
    val selectQuery = dsl.resultQuery("SELECT id, name FROM films WHERE id = ?", "1")

    import csw.database.scaladsl.JooqExtentions.RichResultQuery
    val selectResultF: Future[List[Films]] = selectQuery.fetchAsyncScala[Films]
    selectResultF.foreach(names ⇒ s"Fetched names of films $names")
    //#dsl-fetch

    //#dsl-function
    val functionQuery = dsl
      .query(
        """
        |CREATE FUNCTION inc(val integer) RETURNS integer AS $$
        |BEGIN
        |RETURN val + 1;
        |END; $$
        |LANGUAGE PLPGSQL;
        """.stripMargin
      )

    val functionResultF: Future[Integer] = functionQuery.executeAsyncScala()
    functionResultF.foreach(result ⇒ println(s"Function inc created with $result"))
    //#dsl-function

    Completed(runId)
  }
  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit                                         = ???
  override def validateCommand(runId: Id, controlCommand: ControlCommand): CommandResponse.ValidateCommandResponse = ???
  override def onOneway(runId: Id, controlCommand: ControlCommand): Unit                                           = ???
  override def onShutdown(): Future[Unit]                                                                          = ???
  override def onGoOffline(): Unit                                                                                 = ???
  override def onGoOnline(): Unit                                                                                  = ???
}
