package csw.framework

import csw.framework.models.LocationServiceUsage.{DoNotRegister, RegisterOnly}
import csw.framework.models.{ComponentInfo, ContainerInfo}
import csw.param.models.location.ComponentType.{Assembly, HCD}

import scala.concurrent.duration.DurationDouble

object ComponentInfos {
  val assemblyInfo =
    ComponentInfo(
      "trombone",
      Assembly,
      "wfos",
      "csw.common.components.SampleComponentBehaviorFactory",
      DoNotRegister,
      Set.empty
    )

  val assemblyInfoToSimulateFailure =
    ComponentInfo(
      "trombone",
      Assembly,
      "wfos",
      "csw.common.components.ComponentBehaviorFactoryToSimulateFailure",
      DoNotRegister,
      Set.empty
    )

  val hcdInfo =
    ComponentInfo(
      "SampleHcd",
      HCD,
      "wfos",
      "csw.common.components.SampleComponentBehaviorFactory",
      RegisterOnly,
      Set.empty
    )

  val hcdInfoWithInitializeTimeout = ComponentInfo(
    "SampleHcd",
    HCD,
    "wfos",
    "csw.common.components.SampleComponentBehaviorFactory",
    RegisterOnly,
    Set.empty,
    0.seconds,
    5.seconds
  )

  val hcdInfoWithRunTimeout = ComponentInfo(
    "SampleHcd",
    HCD,
    "wfos",
    "csw.common.components.SampleComponentBehaviorFactory",
    RegisterOnly,
    Set.empty,
    5.seconds,
    0.seconds
  )

  val containerInfo: ContainerInfo = ContainerInfo("container", Set(hcdInfo, assemblyInfo))
}
