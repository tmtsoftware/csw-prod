package csw.framework.command

import akka.actor.Scheduler
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter.UntypedActorSystemOps
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import csw.command.extensions.AkkaLocationExt.RichAkkaLocation
import csw.command.messages.CommandMessage.{Oneway, Submit}
import csw.command.messages.CommandResponseManagerMessage.Subscribe
import csw.common.components.command.ComponentStateForCommand.{acceptedCmd, cancelCmd, prefix}
import csw.framework.internal.wiring.{FrameworkWiring, Standalone}
import csw.location.api.models.Connection.AkkaConnection
import csw.location.api.models.{ComponentId, ComponentType}
import csw.location.helpers.{LSNodeSpec, OneMemberAndSeed}
import csw.params.commands.CommandResponse._
import csw.params.commands.Setup
import csw.params.core.generics.KeyType
import csw.params.core.models.ObsId
import csw.location.server.http.HTTPLocationService
import io.lettuce.core.RedisClient
import org.scalatest.mockito.MockitoSugar

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}

class CancellableCommandTestMultiJvm1 extends CancellableCommandTest(0)

class CancellableCommandTestMultiJvm2 extends CancellableCommandTest(0)

// DEOPSCSW-211 Notification of Interrupted Message
class CancellableCommandTest(ignore: Int)
    extends LSNodeSpec(config = new OneMemberAndSeed, mode = "http")
    with HTTPLocationService
    with MockitoSugar {
  import config._

  implicit val actorSystem: ActorSystem[_] = system.toTyped
  implicit val mat: Materializer = ActorMaterializer()
  implicit val ec: ExecutionContext = actorSystem.executionContext
  implicit val timeout: Timeout = 5.seconds
  implicit val scheduler: Scheduler = actorSystem.scheduler

  override def afterAll(): Unit = super.afterAll()

  test("a long running command should be cancellable") {
    runOn(seed) {
      // spawn container having assembly and hcd running in jvm-1
      val wiring = FrameworkWiring.make(system, locationService, mock[RedisClient])
      val assemblyConf = ConfigFactory.load("command/commanding_assembly.conf")
      Await.result(Standalone.spawn(assemblyConf, wiring), 5.seconds)
      enterBarrier("spawned")
    }

    runOn(member) {
      val submitResponseProbe = TestProbe[SubmitResponse]
      val onewayResponseProbe = TestProbe[OnewayResponse]
      val obsId = Some(ObsId("Obs001"))
      val cancelCmdId = KeyType.StringKey.make("cancelCmdId")

      enterBarrier("spawned")

      // resolve the assembly running on seed
      val assemblyLocF =
        locationService.resolve(AkkaConnection(ComponentId("Monitor_Assembly", ComponentType.Assembly)), 5.seconds)
      val assemblyRef = Await.result(assemblyLocF, 10.seconds).map(_.componentRef).get

      // original command is submit and Cancel command is also submit
      // This returns Started, so it is a long-running and we are free to cancel it
      val originalSetup = Setup(prefix, acceptedCmd, obsId)
      assemblyRef ! Submit(originalSetup, submitResponseProbe.ref)
      submitResponseProbe.expectMessage(Started(originalSetup.runId))

      // This is the cancel command that is processed
      val cancelSetup = Setup(prefix, cancelCmd, obsId, Set(cancelCmdId.set(originalSetup.runId.id)))
      assemblyRef ! Submit(cancelSetup, submitResponseProbe.ref)
      submitResponseProbe.expectMessage(Completed(cancelSetup.runId))

      assemblyRef ! Subscribe(cancelSetup.runId, submitResponseProbe.ref)
      submitResponseProbe.expectMessage(Completed(cancelSetup.runId))

      assemblyRef ! Subscribe(originalSetup.runId, submitResponseProbe.ref)
      submitResponseProbe.expectMessage(Cancelled(originalSetup.runId))

      // original command is submit but Cancel command is oneway
      val originalSetup2 = Setup(prefix, acceptedCmd, obsId)
      assemblyRef ! Submit(originalSetup2, submitResponseProbe.ref)
      submitResponseProbe.expectMessage(Started(originalSetup2.runId))

      val cancelSetup2 = Setup(prefix, cancelCmd, obsId, Set(cancelCmdId.set(originalSetup2.runId.id)))
      assemblyRef ! Oneway(cancelSetup2, onewayResponseProbe.ref)
      onewayResponseProbe.expectMessage(Accepted(cancelSetup2.runId))

      assemblyRef ! Subscribe(originalSetup2.runId, submitResponseProbe.ref)
      submitResponseProbe.expectMessage(Cancelled(originalSetup2.runId))

      // original command is oneway but Cancel command is submit
      val originalSetup3 = Setup(prefix, acceptedCmd, obsId)
      assemblyRef ! Oneway(originalSetup3, onewayResponseProbe.ref)
      onewayResponseProbe.expectMessage(Accepted(originalSetup3.runId))

      val cancelSetup3 = Setup(prefix, cancelCmd, obsId, Set(cancelCmdId.set(originalSetup3.runId.id)))
      assemblyRef ! Submit(cancelSetup3, submitResponseProbe.ref)
      submitResponseProbe.expectMessage(Completed(cancelSetup3.runId))

      // Note that this works, even though initial Oneway was not in CRM, if code puts Cancelled for the runId into CRM
      // the subscribe will work
      assemblyRef ! Subscribe(originalSetup3.runId, submitResponseProbe.ref)
      submitResponseProbe.expectMessage(Cancelled(originalSetup3.runId))

      // original command is oneway and Cancel command is also oneway
      val originalSetup4 = Setup(prefix, acceptedCmd, obsId)
      assemblyRef ! Oneway(originalSetup4, onewayResponseProbe.ref)
      onewayResponseProbe.expectMessage(Accepted(originalSetup4.runId))

      val cancelSetup4 = Setup(prefix, cancelCmd, obsId, Set(cancelCmdId.set(originalSetup4.runId.id)))
      assemblyRef ! Oneway(cancelSetup4, onewayResponseProbe.ref)
      onewayResponseProbe.expectMessage(Accepted(cancelSetup4.runId))
    }
    enterBarrier("end")
  }
}
