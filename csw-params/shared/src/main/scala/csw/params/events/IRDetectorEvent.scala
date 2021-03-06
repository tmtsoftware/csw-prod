package csw.params.events

import csw.params.core.generics.Parameter
import csw.params.core.models.ObsId
import csw.prefix.models.Prefix

object IRDetectorEvent extends DetectorEvent(ObserveEventNames.IRDetectorExposureState) {
  def exposureData(
      sourcePrefix: String,
      obsId: ObsId,
      detector: String,
      readsInRamp: Int,
      readsComplete: Int,
      rampsInExposure: Int,
      rampsComplete: Int,
      exposureTime: Long,
      remainingExposureTime: Long
  ): ObserveEvent = {
    val params: Set[Parameter[_]] = Set(
      ParamFactories.obsIdParam(obsId),
      ParamFactories.detectorParam(detector),
      ParamFactories.readsInRampParam(readsInRamp),
      ParamFactories.readsCompleteParam(readsComplete),
      ParamFactories.rampsInExposureParam(rampsInExposure),
      ParamFactories.rampsCompleteParam(rampsComplete),
      ParamFactories.exposureTimeParam(exposureTime),
      ParamFactories.remainingExposureTimeParam(remainingExposureTime)
    )
    ObserveEvent(Prefix(sourcePrefix), ObserveEventNames.IRDetectorExposureData, params)
  }
}
