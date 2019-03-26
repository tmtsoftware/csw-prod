package org.tmt.nfiraos.samplehcd

import akka.actor.Cancellable
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import csw.command.client.messages.TopLevelActorMessage
import csw.framework.models.CswContext
import csw.framework.scaladsl.ComponentHandlers
import csw.location.api.models.TrackingEvent
import csw.params.commands.CommandResponse._
import csw.params.commands._
import csw.params.core.generics.{Key, KeyType, Parameter}
import csw.params.core.models.Id
import csw.params.events.{EventName, SystemEvent}
import csw.serializable.TMTSerializable

import scala.concurrent.{ExecutionContextExecutor, Future}

/**
 * Domain specific logic should be written in below handlers.
 * These handlers get invoked when component receives messages/commands from other component/entity.
 * For example, if one component sends Submit(Setup(args)) command to SampleHcd, these will be first validated in the
 * supervisor and then forwarded to Component TLA which first invokes validateCommand hook
 * and if validation is successful, then onSubmit hook gets invoked.
 * You can find more information on this here : https://tmtsoftware.github.io/csw/framework.html
 */
class SampleHcdHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext) extends ComponentHandlers(ctx, cswCtx) {
  import cswCtx._
  implicit val ec: ExecutionContextExecutor = ctx.executionContext
  private val log                           = loggerFactory.getLogger

  //#worker-actor
  sealed trait WorkerCommand                      extends TMTSerializable
  case class Sleep(runId: Id, timeInMillis: Long) extends WorkerCommand

  private val workerActor =
    ctx.spawn(
      Behaviors.receiveMessage[WorkerCommand](msg => {
        msg match {
          case sleep: Sleep =>
            log.trace(s"WorkerActor received sleep command with time of ${sleep.timeInMillis} ms")
            // simulate long running command
            Thread.sleep(sleep.timeInMillis)
            commandResponseManager.addOrUpdateCommand(CommandResponse.Completed(sleep.runId))
          case _ => log.error("Unsupported message type")
        }
        Behaviors.same
      }),
      "WorkerActor"
    )
  //#worker-actor

  //#initialize
  var maybePublishingGenerator: Option[Cancellable] = None
  override def initialize(): Future[Unit] = {
    log.info("In HCD initialize")
    maybePublishingGenerator = Some(publishCounter())
    Future.unit
  }

  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit = {
    log.debug(s"TrackingEvent received: ${trackingEvent.connection.name}")
  }

  override def onShutdown(): Future[Unit] = {
    log.info("HCD is shutting down")
    Future.unit
  }
  //#initialize

  //#publish
  import scala.concurrent.duration._
  private def publishCounter(): Cancellable = {
    var counter = 0
    def incrementCounterEvent() = Option {
      counter += 1
      val param: Parameter[Int] = KeyType.IntKey.make("counter").set(counter)
      SystemEvent(componentInfo.prefix, EventName("HcdCounter")).add(param)
    }

    log.info("Starting publish stream.")
    eventService.defaultPublisher.publish(incrementCounterEvent(), 2.second, err => log.error(err.getMessage, ex = err))
  }

  private def stopPublishingGenerator(): Unit = {
    log.info("Stopping publish stream")
    maybePublishingGenerator.foreach(_.cancel)
  }
  //#publish

  //#validate
  override def validateCommand(runId: Id, controlCommand: ControlCommand): ValidateCommandResponse = {
    log.info(s"Validating command: ${controlCommand.commandName.name}")
    controlCommand.commandName.name match {
      case "sleep" => Accepted(runId)
      case x       => Invalid(runId, CommandIssue.UnsupportedCommandIssue(s"Command $x. not supported."))
    }
  }
  //#validate

  //#onSetup
  override def onSubmit(runId: Id, controlCommand: ControlCommand): SubmitResponse = {
    log.info(s"Handling command: ${controlCommand.commandName}")

    controlCommand match {
      case setupCommand: Setup => onSetup(runId, setupCommand)
      case observeCommand: Observe => // implement (or not)
        Error(runId, "Observe not supported")
    }
  }

  def onSetup(runId: Id, setup: Setup): SubmitResponse = {
    val sleepTimeKey: Key[Long] = KeyType.LongKey.make("SleepTime")

    // get param from the Parameter Set in the Setup
    val sleepTimeParam: Parameter[Long] = setup(sleepTimeKey)

    // values of parameters are arrays. Get the first one (the only one in our case) using `head` method available as a convenience method on `Parameter`.
    val sleepTimeInMillis: Long = sleepTimeParam.head

    log.info(s"command payload: ${sleepTimeParam.keyName} = $sleepTimeInMillis")

    workerActor ! Sleep(runId, sleepTimeInMillis)

    Started(runId)
  }
  //#onSetup

  override def onOneway(runId: Id, controlCommand: ControlCommand): Unit = {}

  override def onGoOffline(): Unit = {}

  override def onGoOnline(): Unit = {}

}
