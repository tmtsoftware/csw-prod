package csw.params.events

import csw.params.core.generics.Parameter
import csw.prefix.models.Prefix

object WFSDetectorEvent {
  private def create(sourcePrefix: String, eventName: EventName): ObserveEvent = ObserveEvent(Prefix(sourcePrefix), eventName)

  def publishSuccess(sourcePrefix: String): ObserveEvent = create(sourcePrefix, ObserveEventNames.PublishSuccess)
  def publishFail(sourcePrefix: String): ObserveEvent    = create(sourcePrefix, ObserveEventNames.PublishFail)
  def exposureState(
      sourcePrefix: String,
      detector: String,
      exposureInProgress: Boolean,
      abortInProgress: Boolean,
      isAborted: Boolean,
      operationalState: OperationalState,
      errorMessage: String
  ): ObserveEvent = {
    val params: Set[Parameter[_]] = Set(
      ParamFactories.detectorParam(detector),
      ParamFactories.operationalStateParam(operationalState),
      ParamFactories.errorMessageParam(errorMessage),
      ParamFactories.exposureInProgressParam(exposureInProgress),
      ParamFactories.abortInProgressParam(abortInProgress),
      ParamFactories.isAbortedParam(isAborted)
    )
    ObserveEvent(Prefix(sourcePrefix), ObserveEventNames.WfsDetectorExposureState, params)
  }
}
