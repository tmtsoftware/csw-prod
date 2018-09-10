package csw.messages.commands

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import csw.messages.params.models.Id

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object CommandResponseAggregator {

  /**
   * Creates an aggregated response from a collection of CommandResponses received from other components. If one of the
   * CommandResponses fail, the aggregated response fails and further processing of any more CommandResponse is terminated.
   *
   * @param commandResponses a stream of CommandResponses
   * @return a future of aggregated response
   */
  def aggregateResponse(
      commandResponses: Source[CommandResponse, NotUsed]
  )(implicit ec: ExecutionContext, mat: Materializer): Future[CommandResponse] = {
    commandResponses
      .runForeach { x ⇒
        if (x.resultType == CommandResultType.Negative)
          throw new RuntimeException(s"Command with runId [${x.runId}] failed with response [$x]")
      }
      .transform {
        case Success(_)  ⇒ Success(CommandResponse.Completed(Id()))
        case Failure(ex) ⇒ Success(CommandResponse.Error(Id(), s"${ex.getMessage}"))
      }
  }
}