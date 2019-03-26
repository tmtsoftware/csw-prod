package csw.framework.command

import akka.actor.Scheduler
import akka.actor.testkit.typed.TestKitSettings
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter.UntypedActorSystemOps
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import csw.command.client.CommandServiceFactory
import csw.common.components.command.ComponentStateForCommand._
import csw.framework.internal.wiring.{FrameworkWiring, Standalone}
import csw.location.api.models.Connection.AkkaConnection
import csw.location.api.models.{AkkaLocation, ComponentId, ComponentType}
import csw.location.helpers.{LSNodeSpec, TwoMembersAndSeed}
import csw.location.server.http.MultiNodeHTTPLocationService
import csw.params.commands.CommandIssue.OtherIssue
import csw.params.commands.CommandResponse._
import csw.params.commands.Setup
import csw.params.core.models.ObsId
import csw.params.core.states.{CurrentState, StateName}
import io.lettuce.core.RedisClient
import org.scalatest.concurrent.{PatienceConfiguration, ScalaFutures}
import org.mockito.MockitoSugar

import scala.concurrent.duration.DurationDouble
import scala.concurrent.{Await, ExecutionContextExecutor, Future}

class LongRunningCommandTestMultiJvm1 extends LongRunningCommandTest(0)
class LongRunningCommandTestMultiJvm2 extends LongRunningCommandTest(0)
class LongRunningCommandTestMultiJvm3 extends LongRunningCommandTest(0)

// DEOPSCSW-194: Support long running actions asynchronously
// DEOPSCSW-227: Distribute commands to multiple destinations
// DEOPSCSW-228: Assist Components with command completion
// DEOPSCSW-233: Hide implementation by having a CCS API
class LongRunningCommandTest(ignore: Int)
    extends LSNodeSpec(config = new TwoMembersAndSeed, mode = "http")
    with MultiNodeHTTPLocationService
    with ScalaFutures
    with MockitoSugar {
  import config._

  implicit val actorSystem: ActorSystem[_]  = system.toTyped
  implicit val mat: Materializer            = ActorMaterializer()
  implicit val ec: ExecutionContextExecutor = actorSystem.executionContext
  implicit val timeout: Timeout             = 20.seconds
  implicit val scheduler: Scheduler         = actorSystem.scheduler
  implicit val testkit: TestKitSettings     = TestKitSettings(actorSystem)

  test("should be able to send long running commands asynchronously and get the response") {
    runOn(seed) {
      // cluster seed is running on jvm-1
      enterBarrier("spawned")
      val obsId = ObsId("Obs001")

      // resolve assembly running in jvm-2 and send setup command expecting immediate command completion response
      val assemblyLocF =
        locationService.resolve(
          AkkaConnection(ComponentId("Test_Component_Running_Long_Command", ComponentType.Assembly)),
          5.seconds
        )
      val assemblyLocation: AkkaLocation = Await.result(assemblyLocF, 10.seconds).get
      val assemblyCommandService         = CommandServiceFactory.make(assemblyLocation)

      val assemblyLongSetup = Setup(prefix, longRunning, Some(obsId))
      val probe             = TestProbe[CurrentState]

      //#subscribeCurrentState
      // subscribe to the current state of an assembly component and use a callback which forwards each received
      // element to a test probe actor
      assemblyCommandService.subscribeCurrentState(probe.ref ! _)
      //#subscribeCurrentState

      // send submit with setup to assembly running in JVM-2
      // then assembly will split it into three sub commands [McsAssemblyComponentHandlers]
      // assembly will forward this sub commands to hcd in following sequence
      // 1. longSetup which takes 5 seconds to finish
      // 2. shortSetup which takes 1 second to finish
      // 3. mediumSetup which takes 3 seconds to finish
      //#subscribe-for-result
      val eventualCommandResponse = assemblyCommandService.submit(assemblyLongSetup).map {
        case Invalid(runId, _) ⇒ Error(runId, "")
        case x: SubmitResponse ⇒ x
      }
      //#subscribe-for-result

      Await.result(eventualCommandResponse, 20.seconds) shouldBe a[Completed] //(assemblyLongSetup.runId)

      // verify that commands gets completed in following sequence
      // ShortSetup => MediumSetup => LongSetup
      probe.expectMessage(CurrentState(prefix, StateName("testStateName"), Set(choiceKey.set(shortCmdCompleted))))
      probe.expectMessage(CurrentState(prefix, StateName("testStateName"), Set(choiceKey.set(mediumCmdCompleted))))
      probe.expectMessage(CurrentState(prefix, StateName("testStateName"), Set(choiceKey.set(longCmdCompleted))))
      probe.expectMessage(CurrentState(prefix, StateName("testStateName"), Set(choiceKey.set(longRunningCmdCompleted))))

      //#submit
      val setupForSubscribe = Setup(prefix, longRunning, Some(obsId))
      val response          = assemblyCommandService.submit(setupForSubscribe)
      //#submit

      Await.result(response, 20.seconds) shouldBe a[Completed] //(setupForSubscribe.runId)

      //#query-response
      val setupForQuery = Setup(prefix, longRunning, Some(obsId))
      val submitResponseF = assemblyCommandService.submit(setupForQuery)
      val submitResponse = Await.result(submitResponseF, 5.seconds)

      //do some work before querying for the result of above command as needed
      val eventualResponse: Future[QueryResponse] = assemblyCommandService.query(submitResponse.runId)
      //#query-response
      eventualResponse.map(_ shouldBe Started(submitResponse.runId))

      // Use the initial future to determine the when completed
      submitResponseF.map(_ shouldBe Completed(submitResponse.runId))

      enterBarrier("long-commands")

      val assemblyInvalidSetup = Setup(prefix, invalidCmd, Some(obsId))

      // First test sends two commands that complete immediately successfully
      //#submitAll
      val assemblyInitSetup = Setup(prefix, initCmd, Some(obsId))
      val assemblyMoveSetup = Setup(prefix, moveCmd, Some(obsId))

      // FIXNE FIX  Is this a problem, submitAll has no way to tie responses to Setups except order
      val multiResponse1: Future[List[SubmitResponse]] =
        assemblyCommandService.submitAll(List(assemblyInitSetup, assemblyMoveSetup))
      //#submitAll

      whenReady(multiResponse1, PatienceConfiguration.Timeout(5.seconds)) { result =>
        result.length shouldBe 2
        result.head shouldBe a[Completed] // (assemblyInitSetup.runId)
        result(1) shouldBe a[Completed] // (assemblyMoveSetup.runId)
      }

      // Second test sends three commands with last invalid
      val multiResponse2 = assemblyCommandService.submitAll(List(assemblyInitSetup, assemblyMoveSetup, assemblyInvalidSetup))
      whenReady(multiResponse2, PatienceConfiguration.Timeout(5.seconds)) { result =>
        result.length shouldBe 3
        result(0) shouldBe a[Completed] //(assemblyInitSetup.runId)
        result(1) shouldBe a[Completed] // (assemblyMoveSetup.runId)
        result(2) shouldBe a[Invalid] // (assemblyInvalidSetup.runId, OtherIssue("Invalid"))
        val inv:Invalid = result(2).asInstanceOf[Invalid]
        inv.issue shouldBe OtherIssue("Invalid")
      }

      // Second test sends three commands with second invalid so last one is unexecuted
      val multiResponse3 = assemblyCommandService.submitAll(List(assemblyInitSetup, assemblyInvalidSetup, assemblyMoveSetup))
      whenReady(multiResponse3, PatienceConfiguration.Timeout(5.seconds)) { result =>
        result.length shouldBe 2
        result(0) shouldBe a[Completed] // (assemblyInitSetup.runId)
        result(1) shouldBe a[Invalid] // (assemblyInvalidSetup.runId, OtherIssue("Invalid"))
      }

      // Last test does an init of assembly and then sends the long command
      val multiResponse4 = assemblyCommandService.submitAll(List(assemblyInitSetup, assemblyLongSetup))
      whenReady(multiResponse4, PatienceConfiguration.Timeout(10.seconds)) { result =>
        result.length shouldBe 2
        result(0) shouldBe a[Completed] // (assemblyInitSetup.runId)
        result(1) shouldBe a[Completed] // (assemblyLongSetup.runId)
      }

      enterBarrier("multiple-components-submit-multiple-commands")

      enterBarrier("multiple-components-submit-subscribe-multiple-commands")
    }

    runOn(member1) {
      // spawn single assembly running in Standalone mode in jvm-2
      val wiring       = FrameworkWiring.make(system, locationService, mock[RedisClient])
      val assemblyConf = ConfigFactory.load("command/mcs_assembly.conf")
      Await.result(Standalone.spawn(assemblyConf, wiring), 5.seconds)
      enterBarrier("spawned")
      enterBarrier("long-commands")
      enterBarrier("multiple-components-submit-multiple-commands")
      enterBarrier("multiple-components-submit-subscribe-multiple-commands")
    }

    runOn(member2) {
      // spawn single hcd running in Standalone mode in jvm-3
      val wiring  = FrameworkWiring.make(system, locationService, mock[RedisClient])
      val hcdConf = ConfigFactory.load("command/mcs_hcd.conf")
      Await.result(Standalone.spawn(hcdConf, wiring), 5.seconds)
      enterBarrier("spawned")
      enterBarrier("long-commands")
      enterBarrier("multiple-components-submit-multiple-commands")
      enterBarrier("multiple-components-submit-subscribe-multiple-commands")
    }
    enterBarrier("end")
  }
}
