package csw.framework.command

import akka.actor.Scheduler
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter.UntypedActorSystemOps
import akka.stream.{ActorMaterializer, Materializer}
import akka.actor.testkit.typed.TestKitSettings
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import csw.command.messages.CommandMessage.Submit
import csw.common.utils.LockCommandFactory
import csw.framework.internal.wiring.{Container, FrameworkWiring, Standalone}
import csw.params.commands.CommandResponse._
import csw.params.commands._
import csw.command.models.matchers.MatcherResponses.{MatchCompleted, MatchFailed}
import csw.command.models.matchers.{DemandMatcher, Matcher, MatcherResponse}
import csw.command.models.framework.LockingResponse
import csw.command.models.framework.LockingResponses.LockAcquired
import csw.location.api.models.Connection.AkkaConnection
import csw.location.api.models.{AkkaLocation, ComponentId, ComponentType}
import csw.params.core.generics.{KeyType, Parameter}
import csw.params.core.models.{ObsId, Prefix}
import csw.params.core.states.{DemandState, StateName}
import csw.command.extensions.AkkaLocationExt.RichAkkaLocation
import csw.command.scaladsl.CommandService
import csw.location.helpers.{LSNodeSpec, TwoMembersAndSeed}
import csw.location.server.http.HTTPLocationService
import io.lettuce.core.RedisClient
import org.scalatest.mockito.MockitoSugar

import scala.async.Async._
import scala.concurrent.duration.DurationDouble
import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException}

/**
  * Test Configuration :
  * JVM-1 : Seed node
  * JVM-2 : Assembly running in Standalone mode (Commanding Assembly)
  * JVM-3 : Assembly and HCD running in Container Mode
  *
  * Scenario 1 : Short Running Command
  * 1. Assembly running in JVM-2 (Commanding Assembly) resolves Assembly running in JVM-3
  * 2. Commanding Assembly sends short running command to another assembly (JVM-3)
  * 3. Assembly (JVM-3) receives command and update its status as Invalid in CSRM
  * 4. Commanding Assembly (JVM-2) receives Command Completion response which is Invalid
  *
  * Scenario 2 : Long Running Command without matcher
  * 1. Commanding Assembly sends long running command to another assembly (JVM-3)
  * 2. Assembly (JVM-3) receives command and update its validation status as Accepted in CSRM
  * 3. Commanding Assembly (JVM-2) receives validation response as Accepted
  * 4. Commanding Assembly then waits for Command Completion response
  * 5. Assembly from JVM-3 updates Command Completion status which is CompletedWithResult in CSRM
  * 6. Commanding Assembly (JVM-2) receives Command Completion response which is CompletedWithResult
  *
  * Scenario 3 : Long Running Command with matcher
  * 1. Commanding Assembly sends long running command to another assembly (JVM-3)
  * 2. Assembly (JVM-3) receives command and update its validation status as Accepted in CSRM
  * 3. Commanding Assembly (JVM-2) receives validation response as Accepted
  * 4. Commanding Assembly starts state matcher
  * 5. Assembly (JVM-3) keeps publishing its current state
  * 6. Commanding Assembly marks status of Command as Completed when demand state matches with current state=
  **/
class CommandServiceTestMultiJvm1 extends CommandServiceTest(0)

class CommandServiceTestMultiJvm2 extends CommandServiceTest(0)

class CommandServiceTestMultiJvm3 extends CommandServiceTest(0)

// DEOPSCSW-201: Destination component to receive a submit command
// DEOPSCSW-202: Verification of submit commands
// DEOPSCSW-207: Report on Configuration Command Completion
// DEOPSCSW-208: Report failure on Configuration Completion command
// DEOPSCSW-217: Execute RPC like commands
// DEOPSCSW-222: Locking a component for a specific duration
// DEOPSCSW-224: Inter component command sending
// DEOPSCSW-225: Allow components to receive commands
// DEOPSCSW-228: Assist Components with command completion
// DEOPSCSW-313: Support short running actions by providing immediate response
// DEOPSCSW-321: AkkaLocation provides wrapper for ActorRef[ComponentMessage]
class CommandServiceTest(ignore: Int)
    extends LSNodeSpec(config = new TwoMembersAndSeed, mode = "http")
    with HTTPLocationService
    with MockitoSugar {

  import config._
  import csw.common.components.command.ComponentStateForCommand._

  implicit val actorSystem: ActorSystem[_] = system.toTyped
  implicit val mat: Materializer           = ActorMaterializer()
  implicit val ec: ExecutionContext        = actorSystem.executionContext
  implicit val timeout: Timeout            = 5.seconds
  implicit val scheduler: Scheduler        = actorSystem.scheduler
  implicit val testkit: TestKitSettings    = TestKitSettings(actorSystem)

  override def afterAll(): Unit = super.afterAll()

  test("sender of command should receive appropriate responses") {

    runOn(seed) {
      // cluster seed is running on jvm-1
      enterBarrier("spawned")

      // resolve assembly running in jvm-3 and send setup command expecting immediate command completion response
      val assemblyLocF = locationService.resolve(AkkaConnection(ComponentId("Assembly", ComponentType.Assembly)), 5.seconds)
      val maybeLocation = Await.result(assemblyLocF, 10.seconds)

      maybeLocation.isDefined shouldBe true
      maybeLocation.get.prefix shouldBe Prefix("tcs.mobie.blue.assembly")

      val assemblyRef = maybeLocation.map(_.componentRef).get

      enterBarrier("short-long-commands")
      enterBarrier("assembly-locked")

      val submitResponseProbe = TestProbe[SubmitResponse]

      // try to send a command to assembly which is already locked
      val assemblyObserve = Observe(invalidPrefix, acceptedCmd, Some(ObsId("Obs001")))
      assemblyRef ! Submit(assemblyObserve, submitResponseProbe.ref)
      submitResponseProbe.expectMessageType[Locked]

      enterBarrier("command-when-locked")
    }

    runOn(member1) {
      val obsId = Some(ObsId("Obs001"))

      // spawn single assembly running in Standalone mode in jvm-2
      val wiring = FrameworkWiring.make(system, locationService, mock[RedisClient])
      val sequencerConf = ConfigFactory.load("command/commanding_assembly.conf")
      Await.result(Standalone.spawn(sequencerConf, wiring), 5.seconds)
      enterBarrier("spawned")

      // resolve assembly running in jvm-3 and send setup command expecting immediate command completion response
      val assemblyLocF = locationService.resolve(AkkaConnection(ComponentId("Assembly", ComponentType.Assembly)), 5.seconds)
      val assemblyLocation: AkkaLocation = Await.result(assemblyLocF, 10.seconds).get
      val assemblyComponent = new CommandService(assemblyLocation)

      // DEOPSCSW-233: Hide implementation by having a CCS API
      // short running command
      val shortCommandResponse = Await.result(assemblyComponent.submit(Setup(prefix, invalidCmd, obsId)), timeout.duration)
      shortCommandResponse shouldBe a[Invalid]

      //#immediate-response
      val eventualResponse: Future[SubmitResponse] = async {
        await(assemblyComponent.submit(Setup(prefix, immediateCmd, obsId))) match {
          case response: Completed ⇒
            //do something with completed result
            response
          case otherResponse ⇒
            // do something with other response which is not expected
            otherResponse
        }
      }
      //#immediate-response

      // long running command which does not use matcher
      val setupWithoutMatcher = Setup(prefix, withoutMatcherCmd, obsId)

      // DEOPSCSW-233: Hide implementation by having a CCS API
      val eventualLongCommandResponse = async {
        val initialCommandResponse = await(assemblyComponent.submit(setupWithoutMatcher))
        initialCommandResponse shouldBe an[Started]
        await(assemblyComponent.subscribe(setupWithoutMatcher.runId))
      }

      val longCommandResponse = Await.result(eventualLongCommandResponse, timeout.duration)

      longCommandResponse shouldBe a[CompletedWithResult]
      longCommandResponse.runId shouldBe setupWithoutMatcher.runId

      // DEOPSCSW-229: Provide matchers infrastructure for comparison
      // DEOPSCSW-317: Use state values of HCD to determine command completion
      // long running command which uses matcher
      val param: Parameter[Int] = KeyType.IntKey.make("encoder").set(100)
      val setupWithMatcher = Setup(prefix, matcherCmd, obsId)

      //#matcher

      // create a DemandMatcher which specifies the desired state to be matched.
      val demandMatcher = DemandMatcher(DemandState(prefix, StateName("testStateName"), Set(param)), withUnits = false, timeout)

      // create matcher instance
      val matcher = new Matcher(assemblyLocation.componentRef, demandMatcher)

      // start the matcher so that it is ready to receive state published by the source
      val matcherResponseF: Future[MatcherResponse] = matcher.start

      // submit command and if the command is successfully validated, check for matching of demand state against current state
      val eventualCommandResponse: Future[MatchingResponse] = async {
        val initialResponse = await(assemblyComponent.oneway(setupWithMatcher))
        initialResponse match {
          case _: Accepted ⇒
            val matcherResponse = await(matcherResponseF)
            // create appropriate response if demand state was matched from among the published state or otherwise
            matcherResponse match {
              case MatchCompleted =>
                Completed(setupWithMatcher.runId)
              case mf: MatchFailed =>
                Error(setupWithMatcher.runId, mf.throwable.getMessage)
            }
          case invalid: Invalid ⇒
            matcher.stop()
            invalid
          case locked: Locked =>
            matcher.stop()
            locked
        }
      }

      val commandResponse = Await.result(eventualCommandResponse, timeout.duration)
      //#matcher
      commandResponse shouldBe Completed(setupWithMatcher.runId)

      //#onewayAndMatch
      val eventualResponse1: Future[MatchingResponse] = assemblyComponent.onewayAndMatch(setupWithMatcher, demandMatcher)
      //#onewayAndMatch
      Await.result(eventualResponse1, timeout.duration) shouldBe Completed(setupWithMatcher.runId)

      // Test failed matching
      val setupWithFailedMatcher = Setup(prefix, matcherFailedCmd, obsId)
      val failedMatcher = new Matcher(assemblyLocation.componentRef, demandMatcher)

      val failedMatcherResponseF: Future[MatcherResponse] = failedMatcher.start

      val eventualCommandResponse2: Future[MatchingResponse] = async {
        val initialResponse = await(assemblyComponent.oneway(setupWithFailedMatcher))
        initialResponse match {
          case _: Accepted ⇒
            val matcherResponse = await(failedMatcherResponseF)
            // create appropriate response if demand state was matched from among the published state or otherwise
            matcherResponse match {
              case MatchCompleted ⇒ Completed(setupWithFailedMatcher.runId)
              case MatchFailed(ex) ⇒ Error(setupWithFailedMatcher.runId, ex.getMessage)
            }
          case invalid: Invalid ⇒
            matcher.stop()
            invalid
          case locked: Locked =>
            matcher.stop()
            locked
        }
      }

      val commandResponse2 = Await.result(eventualCommandResponse2, timeout.duration.+(1.second))
      commandResponse2 shouldBe an[Error]
      commandResponse2.runId shouldBe setupWithFailedMatcher.runId

      // DEOPSCSW-233: Hide implementation by having a CCS API
      // DEOPSCSW-317: Use state values of HCD to determine command completion
      // simulate a scenario where timeout occurs while matching demand state vs current state
      // 1. Demand matcher expect matching to be done in 500 millis
      // 2. Assembly on receiving setupWithTimeoutMatcher command, sleeps for 1 second
      // 3. This results in Timeout in Matcher
      val demandMatcherToSimulateTimeout =
      DemandMatcher(DemandState(prefix, StateName("testStateName"), Set(param)), withUnits = false, 500.millis)
      val setupWithTimeoutMatcher = Setup(prefix, matcherTimeoutCmd, obsId)
      val matcherForTimeout = new Matcher(assemblyLocation.componentRef, demandMatcherToSimulateTimeout)

      val matcherResponseF1: Future[MatcherResponse] = matcherForTimeout.start

      val timeoutExMsg = "The stream has not been completed in 500 milliseconds."
      val eventualCommandResponse1: Future[MatchingResponse] = async {
        val initialResponse = await(assemblyComponent.oneway(setupWithTimeoutMatcher))
        initialResponse match {
          case _: Accepted ⇒
            val matcherResponse = await(matcherResponseF1)
            matcherResponse match {
              case MatchCompleted ⇒ Completed(setupWithMatcher.runId)
              case MatchFailed(ex) if ex.isInstanceOf[TimeoutException] ⇒ Error(setupWithMatcher.runId, timeoutExMsg)
              case MatchFailed(ex) ⇒ Error(setupWithMatcher.runId, ex.getMessage)
            }
          case other@(Invalid(_, _) | Locked(_)) =>
            matcher.stop()
            other.asInstanceOf[MatchingResponse]
        }
      }

      val commandResponseOnTimeout: MatchingResponse = Await.result(eventualCommandResponse1, timeout.duration)
      commandResponseOnTimeout shouldBe a[Error]
      commandResponseOnTimeout.asInstanceOf[Error].message shouldBe timeoutExMsg

      //#oneway
      // `setupWithTimeoutMatcher` is a sample setup payload intended to be used when command response is not determined
      // using matcher
      // Note this and the next two don't do anything useful
      val onewayCommandResponseF: Future[Unit] = async {
        val initialResponse: OnewayResponse = await(assemblyComponent.oneway(setupWithTimeoutMatcher))
        initialResponse match {
          case accepted: Accepted ⇒
          // do Something
          case invalid: Invalid ⇒
          // do Something
          case x ⇒
          // do Something
        }
      }
      //#oneway

      //#submit
      // `setupWithTimeoutMatcher` is a sample setup payload intended to be used when command response is not determined
      // using matcher
      val submitCommandResponseF: Future[Unit] = async {
        val initialResponse: SubmitResponse = await(assemblyComponent.submit(setupWithTimeoutMatcher))
        initialResponse match {
          case accepted: Accepted ⇒
          // do Something
          case invalid: Invalid ⇒
          // do Something
          case x ⇒
          // do Something
        }
      }
      //#submit

      enterBarrier("short-long-commands")

      // acquire lock on assembly
      val lockResponseProbe = TestProbe[LockingResponse]
      assemblyLocation.componentRef ! LockCommandFactory.make(prefix, lockResponseProbe.ref)
      lockResponseProbe.expectMessage(LockAcquired)

      enterBarrier("assembly-locked")

      val submitResponseProbe = TestProbe[SubmitResponse]

      // send command with lock token and expect command processing response
      val assemblySetup = Setup(prefix, immediateCmd, obsId)
      assemblyLocation.componentRef ! Submit(assemblySetup, submitResponseProbe.ref)
      submitResponseProbe.expectMessage(5.seconds, Completed(assemblySetup.runId))

      // send command with lock token and expect command processing response with result
      val assemblySetup2 = Setup(prefix, immediateResCmd, obsId)
      assemblyLocation.componentRef ! Submit(assemblySetup2, submitResponseProbe.ref)
      submitResponseProbe.expectMessageType[CompletedWithResult](5.seconds)

      enterBarrier("command-when-locked")
    }

    runOn(member2) {
      // spawn container having assembly and hcd running in jvm-3
      val wiring = FrameworkWiring.make(system, locationService, mock[RedisClient])
      val containerConf = ConfigFactory.load("command/container.conf")
      Await.result(Container.spawn(containerConf, wiring), 5.seconds)
      enterBarrier("spawned")
      enterBarrier("short-long-commands")
      enterBarrier("assembly-locked")
      enterBarrier("command-when-locked")
    }

    enterBarrier("end")
  }

}
