package csw.command.api.scaladsl

import csw.params.commands.CommandResponse.SubmitResponse
import csw.params.commands.Sequence

import scala.concurrent.Future

/**
 * A command Service API of a sequencer. This model provides method based APIs for command interactions with a sequencer.
 */
trait SequencerCommandService {

  /**
   * Submit given sequence and returns [[csw.params.commands.CommandResponse.SubmitResponse]] as a Future.
   *
   * @param sequence the [[csw.params.commands.Sequence]] payload
   * @return a SubmitResponse as a Future value
   */
  def submitAndWait(sequence: Sequence): Future[SubmitResponse]
}
