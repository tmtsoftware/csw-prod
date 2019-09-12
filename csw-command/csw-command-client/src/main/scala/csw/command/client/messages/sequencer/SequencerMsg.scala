package csw.command.client.messages.sequencer

import akka.actor.typed.ActorRef
import csw.params.commands.CommandResponse.SubmitResponse
import csw.params.commands.Sequence
import csw.serializable.CommandSerializable

trait SequencerMsg
final case class SubmitSequenceAndWait(sequence: Sequence, replyTo: ActorRef[SubmitResponse])
    extends SequencerMsg
    with CommandSerializable
