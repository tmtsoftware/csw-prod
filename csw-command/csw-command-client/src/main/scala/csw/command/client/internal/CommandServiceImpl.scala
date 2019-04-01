package csw.command.client.internal

import java.util.concurrent.TimeoutException

import akka.actor.Scheduler
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.Timeout
import csw.command.api.scaladsl.CommandService
import csw.command.api.{CurrentStateSubscription, StateMatcher}
import csw.command.client.extensions.AkkaLocationExt.RichAkkaLocation
import csw.command.client.messages.CommandMessage.{Oneway, Submit, Validate}
import csw.command.client.messages.{CommandResponseManagerMessage, ComponentMessage}
import csw.command.client.models.matchers.Matcher
import csw.command.client.models.matchers.MatcherResponses.{MatchCompleted, MatchFailed}
import csw.location.api.models.AkkaLocation
import csw.params.commands.CommandResponse._
import csw.params.commands.ControlCommand
import csw.params.core.models.Id
import csw.params.core.states.{CurrentState, StateName}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

private[command] class CommandServiceImpl(componentLocation: AkkaLocation)(implicit val actorSystem: ActorSystem[_])
    extends CommandService {

  private implicit val ec: ExecutionContext = actorSystem.executionContext
  private implicit val mat: Materializer    = ActorMaterializer()(actorSystem.toUntyped)
  private implicit val scheduler: Scheduler = actorSystem.scheduler

  private val component: ActorRef[ComponentMessage] = componentLocation.componentRef
  private val ValidateTimeout                       = 1.seconds

  override def validate(controlCommand: ControlCommand): Future[ValidateResponse] = {
    implicit val timeout: Timeout = Timeout(ValidateTimeout)
    component ? (Validate(controlCommand, _))
  }

  override def submit(controlCommand: ControlCommand)(implicit timeout: Timeout): Future[SubmitResponse] =
    submitOnly(controlCommand).flatMap {
      case s: Started ⇒ component ? (CommandResponseManagerMessage.Subscribe(s.runId, _))
      case x          ⇒ Future.successful(x)
    }

  override def submitOnly(controlCommand: ControlCommand)(implicit timeout: Timeout): Future[SubmitResponse] =
    component ? (Submit(controlCommand, _))

  override def submitAll(
      submitCommands: List[ControlCommand]
  )(implicit timeout: Timeout): Future[List[SubmitResponse]] = {
    // This exception is used to pass the failing command response to the recover to shut down the stream
    class CommandFailureException(val r: SubmitResponse) extends Exception(r.toString)

    Source(submitCommands)
      .mapAsync(1)(submit)
      .map { response =>
        if (isNegative(response))
          throw new CommandFailureException(response)
        else
          response
      }
      .recover {
        // If the command fails, then terminate but return the last response giving the problem, others are ignored
        case ex: CommandFailureException => ex.r
      }
      .toMat(Sink.seq)(Keep.right)
      .run()
      .map(_.toList)
  }

  override def oneway(controlCommand: ControlCommand)(implicit timeout: Timeout): Future[OnewayResponse] =
    component ? (Oneway(controlCommand, _))

  override def onewayAndMatch(
      controlCommand: ControlCommand,
      stateMatcher: StateMatcher
  )(implicit timeout: Timeout): Future[MatchingResponse] = {
    val matcher          = new Matcher(component, stateMatcher)
    val matcherResponseF = matcher.start
    oneway(controlCommand).flatMap {
      case Accepted(runId) ⇒
        matcherResponseF.map {
          case MatchCompleted  ⇒ Completed(runId)
          case MatchFailed(ex) ⇒ Error(runId, ex.getMessage)
        }
      case x @ _ =>
        matcher.stop()
        Future.successful(x.asInstanceOf[MatchingResponse])
    }
  }

  // components coming via this api will be removed from  subscriber's list after timeout
  override def query(commandRunId: Id)(implicit timeout: Timeout): Future[QueryResponse] = {
    val eventualResponse: Future[QueryResponse] = component ? (CommandResponseManagerMessage.Query(commandRunId, _))
    eventualResponse recover {
      case _: TimeoutException ⇒ CommandNotAvailable(commandRunId)
    }
  }

  // components coming via this api will be removed from  subscriber's list after timeout
  override def queryFinal(commandRunId: Id)(implicit timeout: Timeout): Future[SubmitResponse] =
    component ? (CommandResponseManagerMessage.Subscribe(commandRunId, _))

  // components coming via this api will be removed from  subscriber's list after timeout
  override def whenFinal(submit: Future[SubmitResponse])(implicit timeout: Timeout): Future[SubmitResponse] = {
    submit.flatMap(x => queryFinal(x.runId))
  }

    //component ? (CommandResponseManagerMessage.Subscribe(commandRunId, _))

  override def subscribeCurrentState(callback: CurrentState ⇒ Unit): CurrentStateSubscription =
    new CurrentStateSubscriptionImpl(component, None, callback)

  override def subscribeCurrentState(names: Set[StateName], callback: CurrentState ⇒ Unit): CurrentStateSubscription =
    new CurrentStateSubscriptionImpl(component, Some(names), callback)

}
