package csw.command.client.internal

import akka.actor.Scheduler
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.util.Timeout
import csw.command.api.scaladsl.SequencerCommandService
import csw.command.client.extensions.AkkaLocationExt.RichAkkaLocation
import csw.command.client.messages.sequencer.SubmitSequenceAndWait
import csw.location.models.AkkaLocation
import csw.params.commands.CommandResponse.SubmitResponse
import csw.params.commands.Sequence

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class SequencerCommandServiceImpl(sequencerLocation: AkkaLocation)(
    implicit system: ActorSystem[_]
) extends SequencerCommandService {
  private implicit val timeout: Timeout     = Timeout(10.hour)
  private implicit val scheduler: Scheduler = system.scheduler

  private val sequencer: ActorRef[SubmitSequenceAndWait] = sequencerLocation.sequencerRef

  override def submitAndWait(sequence: Sequence): Future[SubmitResponse] =
    sequencer ? (SubmitSequenceAndWait(sequence, _))
}
