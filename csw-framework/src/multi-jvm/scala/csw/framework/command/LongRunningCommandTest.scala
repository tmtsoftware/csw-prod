package csw.framework.command

import akka.actor.Scheduler
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter.UntypedActorSystemOps
import akka.stream.{ActorMaterializer, Materializer}
import akka.actor.testkit.typed.TestKitSettings
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import csw.common.components.command.ComponentStateForCommand._
import csw.framework.internal.wiring.{FrameworkWiring, Standalone}
import csw.params.commands.CommandResponse._
import csw.params.commands.{CommandResponse, Setup}
import csw.location.api.models.Connection.AkkaConnection
import csw.location.api.models.{AkkaLocation, ComponentId, ComponentType}
import csw.params.core.models.ObsId
import csw.params.core.states.{CurrentState, StateName}
import csw.command.scaladsl.{CommandDistributor, CommandService}
import csw.location.helpers.{LSNodeSpec, TwoMembersAndSeed}
import csw.location.server.http.HTTPLocationService
import io.lettuce.core.RedisClient
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar

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
    with HTTPLocationService
    with ScalaFutures
    with MockitoSugar {
  import config._

  implicit val actorSystem: ActorSystem[_]  = system.toTyped
  implicit val mat: Materializer            = ActorMaterializer()
  implicit val ec: ExecutionContextExecutor = actorSystem.executionContext
  implicit val timeout: Timeout             = 20.seconds
  implicit val scheduler: Scheduler         = actorSystem.scheduler
  implicit val testkit: TestKitSettings     = TestKitSettings(actorSystem)

  override def afterAll(): Unit = super.afterAll()

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
      val assemblyCommandService = new CommandService(assemblyLocation)

      val setup = Setup(prefix, longRunning, Some(obsId))
      val probe = TestProbe[CurrentState]

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
      val eventualCommandResponse = assemblyCommandService.submit(setup).flatMap {
        case _: Started ⇒
          assemblyCommandService.subscribe(setup.runId)
        case _ ⇒ Future(Error(setup.runId, ""))
      }
      //#subscribe-for-result

      Await.result(eventualCommandResponse, 20.seconds) shouldBe Completed(setup.runId)

      //#submitAndSubscribe
      val setupForSubscribe = Setup(prefix, longRunning, Some(obsId))
      val response          = assemblyCommandService.submitAndSubscribe(setupForSubscribe)
      //#submitAndSubscribe

      Await.result(response, 20.seconds) shouldBe Completed(setupForSubscribe.runId)

      // verify that commands gets completed in following sequence
      // ShortSetup => MediumSetup => LongSetup
      probe.expectMessage(CurrentState(prefix, StateName("testStateName"), Set(choiceKey.set(shortCmdCompleted))))
      probe.expectMessage(CurrentState(prefix, StateName("testStateName"), Set(choiceKey.set(mediumCmdCompleted))))
      probe.expectMessage(CurrentState(prefix, StateName("testStateName"), Set(choiceKey.set(longCmdCompleted))))
      probe.expectMessage(CurrentState(prefix, StateName("testStateName"), Set(choiceKey.set(longRunningCmdCompleted))))

      //#query-response
      val setupForQuery = Setup(prefix, longRunning, Some(obsId))
      assemblyCommandService.submit(setupForQuery)

      //do some work before querying for the result of above command as needed
      val eventualResponse: Future[QueryResponse] = assemblyCommandService.query(setupForQuery.runId)
      //#query-response
      eventualResponse.map(_ shouldBe Started(setupForQuery.runId))

      enterBarrier("long-commands")
/*
      val hcdLocF =
        locationService.resolve(
          AkkaConnection(ComponentId("Test_Component_Running_Long_Command", ComponentType.HCD)),
          5.seconds
        )
        */
      //val hcdLocation: AkkaLocation = Await.result(hcdLocF, 10.seconds).get
      //val hcdComponent              = new CommandService(hcdLocation)

      val setupAssembly1 = Setup(prefix, moveCmd, Some(obsId))
      //val setupAssembly2 = Setup(prefix, initCmd, Some(obsId))
      //val setupAssembly3 = Setup(prefix, invalidCmd, Some(obsId))
      //val setupHcd1      = Setup(prefix, shortRunning, Some(obsId))
      //val setupHcd2      = Setup(prefix, mediumRunning, Some(obsId))
      //val setupHcd3      = Setup(prefix, failureAfterValidationCmd, Some(obsId))

      //#submitAllAndGetResponse
/*
      val responseOfMultipleCommands = hcdComponent.submitAllAndGetResponse(Set(setupHcd1, setupHcd2))

      //#submitAllAndGetResponse
      whenReady(responseOfMultipleCommands, PatienceConfiguration.Timeout(20.seconds)) { result ⇒
        result shouldBe a[Completed]
      }

      //#aggregated-validation
      val aggregatedValidationResponse = CommandDistributor(
        Map(assemblyCommandService → Set(setupAssembly1, setupAssembly2), hcdComponent → Set(setupHcd1, setupHcd2))
      ).aggregatedValidationResponse()
      //#aggregated-validation

      whenReady(aggregatedValidationResponse, PatienceConfiguration.Timeout(20.seconds)) { result ⇒
        result shouldBe a[Invalid]
      }

      // Test failed validation in one more more commands
      val aggregatedInvalidValidationResponse = CommandDistributor(
        Map(assemblyCommandService → Set(setupAssembly1, setupAssembly2, setupAssembly3),
            hcdComponent           → Set(setupHcd1, setupHcd2))
      ).aggregatedValidationResponse()

      whenReady(aggregatedInvalidValidationResponse, PatienceConfiguration.Timeout(20.seconds)) { result ⇒
        result shouldBe a[Invalid]
      }
*/
      enterBarrier("multiple-components-submit-multiple-commands")

      //#submitAllAndGetFinalResponse
/*
      val finalResponseOfMultipleCommands = hcdComponent.submitAllAndGetFinalResponse(Set(setupHcd1, setupHcd2))

      //#submitAllAndGetFinalResponse
      whenReady(finalResponseOfMultipleCommands, PatienceConfiguration.Timeout(20.seconds)) { result ⇒
        result shouldBe a[Completed]
      }

      //#aggregated-completion
      val aggregatedResponse = CommandDistributor(
        Map(assemblyCommandService → Set(setupAssembly1, setupAssembly2), hcdComponent → Set(setupHcd1, setupHcd2))
      ).aggregatedCompletionResponse()
      //#aggregated-completion

      whenReady(aggregatedResponse, PatienceConfiguration.Timeout(20.seconds)) { result ⇒
        result shouldBe a[Completed]
      }

      val aggregatedErrorResponse = CommandDistributor(
        Map(assemblyCommandService → Set(setupAssembly1, setupAssembly2), hcdComponent → Set(setupHcd1, setupHcd2, setupHcd3))
      ).aggregatedCompletionResponse()

      whenReady(aggregatedErrorResponse, PatienceConfiguration.Timeout(20.seconds)) { result ⇒
        result shouldBe a[Error]
      }
*/
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
