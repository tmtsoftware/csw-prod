package csw.framework

import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.{actor, testkit, Done}
import csw.alarm.api.scaladsl.AlarmService
import csw.command.CommandResponseManager
import csw.command.messages.CommandResponseManagerMessage
import csw.command.models.framework.{LifecycleStateChanged, PubSub}
import csw.config.api.scaladsl.ConfigClientService
import csw.config.client.scaladsl.ConfigClientFactory
import csw.event.api.scaladsl.EventService
import csw.event.client.EventServiceFactory
import csw.framework.internal.pubsub.PubSubBehaviorFactory
import csw.framework.models.CswContext
import csw.framework.scaladsl.RegistrationFactory
import csw.location.api.models.Connection.AkkaConnection
import csw.location.api.models.{AkkaRegistration, RegistrationResult}
import csw.location.api.scaladsl.LocationService
import csw.logging.scaladsl.{Logger, LoggerFactory}
import csw.params.commands.CommandResponse.SubmitResponse
import csw.params.core.models.{Id, Prefix}
import csw.params.core.states.CurrentState
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{when, _}
import org.scalatest.mockito.MockitoSugar

import scala.concurrent.Future

class FrameworkTestMocks(implicit untypedSystem: actor.ActorSystem, system: ActorSystem[Nothing]) extends MockitoSugar {

  ///////////////////////////////////////////////
  val testActor: ActorRef[Any]                 = testkit.TestProbe("test-probe").testActor
  val akkaRegistration                         = AkkaRegistration(mock[AkkaConnection], Prefix("nfiraos.ncc.trombone"), testActor)
  val locationService: LocationService         = mock[LocationService]
  val eventServiceFactory: EventServiceFactory = mock[EventServiceFactory]
  val eventService: EventService               = mock[EventService]
  val alarmService: AlarmService               = mock[AlarmService]
  val registrationResult: RegistrationResult   = mock[RegistrationResult]
  val registrationFactory: RegistrationFactory = mock[RegistrationFactory]

  when(registrationFactory.akkaTyped(any[AkkaConnection], any[Prefix], any[ActorRef[_]]))
    .thenReturn(akkaRegistration)
  when(locationService.register(akkaRegistration)).thenReturn(Future.successful(registrationResult))
  when(locationService.unregister(any[AkkaConnection])).thenReturn(Future.successful(Done))
  when(eventServiceFactory.make(any[LocationService]())(any[actor.ActorSystem]())).thenReturn(eventService)
  when(eventService.executionContext).thenReturn(untypedSystem.dispatcher)
  ///////////////////////////////////////////////

  val commandResponseManagerActor: TestProbe[CommandResponseManagerMessage] = TestProbe[CommandResponseManagerMessage]
  val commandResponseManager: CommandResponseManager                        = mock[CommandResponseManager]

  when(commandResponseManager.commandResponseManagerActor).thenReturn(commandResponseManagerActor.ref)
  doNothing().when(commandResponseManager).addOrUpdateCommand(any[Id], any[SubmitResponse])

  val lifecycleStateProbe: TestProbe[LifecycleStateChanged] = TestProbe[LifecycleStateChanged]
  val compStateProbe: TestProbe[CurrentState]               = TestProbe[CurrentState]

  ///////////////////////////////////////////////
  val loggerFactory: LoggerFactory = mock[LoggerFactory]
  val logger: Logger               = mock[Logger]

  when(loggerFactory.getLogger).thenReturn(logger)
  when(loggerFactory.getLogger(any[actor.ActorContext])).thenReturn(logger)
  when(loggerFactory.getLogger(any[ActorContext[_]])).thenReturn(logger)

  ///////////////////////////////////////////////
  val pubSubComponentActor: ActorRef[PubSub[CurrentState]] =
    untypedSystem.spawnAnonymous(new PubSubBehaviorFactory().make[CurrentState]("pub-sub-component", loggerFactory))
  val currentStatePublisher = new CurrentStatePublisher(pubSubComponentActor)

  ///////////////////////////////////////////////
  val configClientService: ConfigClientService = ConfigClientFactory.clientApi(untypedSystem, locationService)

  val cswCtx: CswContext =
    new CswContext(
      locationService,
      eventService,
      alarmService,
      loggerFactory,
      configClientService,
      currentStatePublisher,
      commandResponseManager,
      ComponentInfos.dummyInfo
    )
}
