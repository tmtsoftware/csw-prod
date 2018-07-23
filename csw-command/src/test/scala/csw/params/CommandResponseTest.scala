package csw.params

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import akka.stream.{ActorMaterializer, Materializer}
import csw.command.models.CommandResponseAggregator
import csw.params.commands.CommandResponse
import csw.params.commands.CommandResponse.{Completed, Error}
import csw.params.core.models.Id
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, FunSuite, Matchers}

import scala.concurrent.{ExecutionContext, Future}

class CommandResponseTest extends FunSuite with Matchers with ScalaFutures with BeforeAndAfterAll {

  implicit val actorSysytem: ActorSystem = ActorSystem("test")
  implicit val ec: ExecutionContext      = actorSysytem.dispatcher
  implicit val mat: Materializer         = ActorMaterializer()

  override def afterAll(): Unit = {
    actorSysytem.terminate()
  }

  test("aggregateResponse should return a aggregated response as AggregatedResponseError if one of the responses failed") {

    val commandResponseSuccess1 = Future { Completed(Id()) }
    val commandResponseFailure  = Future { Error(Id(), "test Error") }
    val commandResponseSuccess2 = Future { Completed(Id()) }

    val source: Source[SubmitResponse, NotUsed] =
      Source(List(commandResponseSuccess1, commandResponseSuccess2, commandResponseFailure)).flatMapMerge(10, Source.fromFuture)

    whenReady(Responses.aggregateResponse(source)) { result ⇒
      result shouldBe a[Responses.Error]
    whenReady(CommandResponseAggregator.aggregateResponse(source)) { result ⇒
      result shouldBe a[commands.CommandResponse.Error]
    }
  }

  test(
    "aggregateResponse should return a aggregated response as AggregatedResponseCompleted if all of the responses are successful"
  ) {
    val commandResponseSuccess1 = Future { Completed(Id()) }
    val commandResponseSuccess2 = Future { Completed(Id()) }
    val commandResponseSuccess3 = Future { Completed(Id()) }

    val source: Source[SubmitResponse, NotUsed] =
      Source(List(commandResponseSuccess1, commandResponseSuccess2, commandResponseSuccess3))
        .flatMapMerge(10, Source.fromFuture)

    whenReady(Responses.aggregateResponse(source)) { result ⇒
      result shouldBe a[Responses.Completed]
    whenReady(CommandResponseAggregator.aggregateResponse(source)) { result ⇒
      result shouldBe a[commands.CommandResponse.Completed]
    }

  }
}
