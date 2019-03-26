package csw.framework.command

import akka.actor.Scheduler
import akka.actor.testkit.typed.TestKitSettings
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter.UntypedActorSystemOps
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import csw.command.api.StateMatcher
import csw.command.client.CommandServiceFactory
import csw.command.client.extensions.AkkaLocationExt.RichAkkaLocation
import csw.command.client.messages.CommandMessage.Submit
import csw.command.client.models.framework.LockingResponse
import csw.command.client.models.framework.LockingResponses.LockAcquired
import csw.command.client.models.matchers.MatcherResponses.{MatchCompleted, MatchFailed}
import csw.command.client.models.matchers.{DemandMatcher, Matcher, MatcherResponse}
import csw.common.utils.LockCommandFactory
import csw.framework.internal.wiring.{Container, FrameworkWiring, Standalone}
import csw.location.api.models.Connection.AkkaConnection
import csw.location.api.models.{AkkaLocation, ComponentId, ComponentType}
import csw.location.helpers.{LSNodeSpec, TwoMembersAndSeed}
import csw.location.server.http.MultiNodeHTTPLocationService
import csw.params.commands.CommandResponse._
import csw.params.commands._
import csw.params.core.generics.Parameter
import csw.params.core.models.{Id, ObsId, Prefix}
import csw.params.core.states.{CurrentState, DemandState, StateName}
import io.lettuce.core.RedisClient
import org.mockito.MockitoSugar

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
// DEOPSCSW-623: Make query wait till Started
class CommandServiceTest(ignore: Int)
    extends LSNodeSpec(config = new TwoMembersAndSeed, mode = "http")
    with MultiNodeHTTPLocationService
    with MockitoSugar {

  import config._
  import csw.common.components.command.ComponentStateForCommand._

  implicit val actorSystem: ActorSystem[_] = system.toTyped
  implicit val mat: Materializer           = ActorMaterializer()
  implicit val ec: ExecutionContext        = actorSystem.executionContext
  implicit val timeout: Timeout            = 5.seconds
  implicit val scheduler: Scheduler        = actorSystem.scheduler
  implicit val testkit: TestKitSettings    = TestKitSettings(actorSystem)

  test("sender of command should receive appropriate responses") {

    runOn(seed) {
      // cluster seed is running on jvm-1
      enterBarrier("spawned")

      // resolve assembly running in jvm-3 and send setup command expecting immediate command completion response
      val assemblyLocF  = locationService.resolve(AkkaConnection(ComponentId("Assembly", ComponentType.Assembly)), 5.seconds)
      val maybeLocation = Await.result(assemblyLocF, 10.seconds)

      maybeLocation.isDefined shouldBe true
      maybeLocation.get.prefix shouldBe Prefix("wfos.blue.assembly")

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
      val wiring        = FrameworkWiring.make(system, locationService, mock[RedisClient])
      val sequencerConf = ConfigFactory.load("command/commanding_assembly.conf")
      Await.result(Standalone.spawn(sequencerConf, wiring), 5.seconds)
      enterBarrier("spawned")

      // resolve assembly running in jvm-3 and send setup command expecting immediate command completion response
      val assemblyLocF                   = locationService.resolve(AkkaConnection(ComponentId("Assembly", ComponentType.Assembly)), 5.seconds)
      val assemblyLocation: AkkaLocation = Await.result(assemblyLocF, 10.seconds).get
      val assemblyCmdService             = CommandServiceFactory.make(assemblyLocation)

      // resolve assembly running in jvm-3 and send setup command expecting immediate command completion response
      val hcdLocF                   = locationService.resolve(AkkaConnection(ComponentId("HCD", ComponentType.HCD)), 5.seconds)
      val hcdLocation: AkkaLocation = Await.result(hcdLocF, 10.seconds).get
      val hcdCmdService             = CommandServiceFactory.make(hcdLocation)

      //#invalidCmd
      val invalidSetup    = Setup(prefix, invalidCmd, obsId)
      val invalidCommandF = assemblyCmdService.submit(invalidSetup)
      async {
        await(invalidCommandF) match {
          case Completed(runId) =>
          // Do Completed thing
          case Invalid(runId, issue) =>
            issue shouldBe a[Invalid]
          case other =>
            // Unexpected result
            log.error(s"Some other response: $other")
        }
      }
      //#invalidCmd

      // DEOPSCSW-233: Hide implementation by having a CCS API
      // short running command
      //#immediate-response
      val immediateSetup = Setup(prefix, immediateCmd, obsId)
      val immediateCommandF = async {
        await(assemblyCmdService.submit(immediateSetup)) match {
          case response: Completed ⇒
            //do something with completed result
            response
          case otherResponse ⇒
            // do something with other response which is not expected
            otherResponse
        }
      }
      //#immediate-response
      Await.result(immediateCommandF, timeout.duration) shouldBe a[Completed] //(immediateSetup.runId)

      // #longRunning
      val longRunningSetup = Setup(prefix, longRunningCmd, obsId)

      val longRunningResultF = async {
        await(assemblyCmdService.submit(longRunningSetup)) match {
          case CompletedWithResult(_, result) =>
            Some(result(encoder).head)
          case otherResponse =>
            // log a message?
            None
        }
      }

      // #longRunning
      val longRunningResult = Await.result(longRunningResultF, timeout.duration)
      longRunningResult shouldBe Some(20)

      // DEOPSCSW-233: Hide implementation by having a CCS API
      // long running command which does not use matcher
      // #queryLongRunning
      val longRunningQueryResultF = async {
        // The following val is set so we can do query and work and complete later
        val longRunningF = assemblyCmdService.submit(longRunningSetup)

        /* Can no longer do QUERY unless you wait for Accepted/Started
        await(assemblyCmdService.query(longRunningSetup2.runId)) match {
          case Started(runId) =>
          // happy case - no action needed
          // Do some other work
          case a =>
          // log.error. This indicates that the command probably failed to start.
        }
        */

        // Now wait for completion and result
        await(longRunningF) match {
          case CompletedWithResult(_, result) =>
            Some(result(encoder).head)

          case otherResponse =>
            // log a message?
            None
        }
      }
      Await.result(longRunningQueryResultF, timeout.duration) shouldBe Some(20)
      // #queryLongRunning

      // This test shows DEOPSCSW-623 because submit is issued without future and queryFinal works
      // #queryFinal
      val queryFinalF = async {
        // The following submit is made without saving the Future!
        val submitResult = Await.result(assemblyCmdService.submit(longRunningSetup), timeout.duration)

        // Use queryFinal and runId to wait for completion and result
        await(assemblyCmdService.queryFinal(submitResult.runId)) match {
          case CompletedWithResult(_, result) =>
            Some(result(encoder).head)

          case otherResponse =>
            // log a message?
            None
        }
      }
      Await.result(queryFinalF, timeout.duration) shouldBe Some(20)
      // #queryFinal

      //#oneway
      // `onewayCmd` is a sample to demonstrate oneway without any actions
      val onewaySetup = Setup(prefix, onewayCmd, obsId)
      // Don't care about the futures from async
      val oneWayF = async {
        await(assemblyCmdService.oneway(onewaySetup)) match {
          case invalid: Invalid ⇒
          // Log an error here
          case _ ⇒
          // Ignore anything other than invalid
        }
      }
      Await.ready(oneWayF, timeout.duration)
      //#oneway

      //#validate
      val validateCommandF = async {
        await(assemblyCmdService.validate(immediateSetup)) match {
          case response: Accepted ⇒ true
          case Invalid(_, issue)  ⇒
            // do something with other response which is not expected
            log.error(s"Command failed to validate with issue: $issue")
            false
          case l: Locked => false
        }
      }
      Await.result(validateCommandF, timeout.duration) shouldBe true
      //#validate

      // test CommandNotAvailable after timeout of 1 seconds
      Await.result(assemblyCmdService.query(Id("blah"))(1.seconds), 2.seconds) shouldEqual CommandNotAvailable(Id("blah"))

      //#query
      // Check on a command that was completed in the past
      /* FIX FIX Does Query survive?
      val queryValue = Await.result(assemblyCmdService.query(longRunningSetup2.runId), timeout.duration)
      queryValue shouldBe a[CompletedWithResult]
      //#query
*/
      val submitAllSetup1       = Setup(prefix, immediateCmd, obsId)
      val submitAllSetup2       = Setup(prefix, longRunningCmd, obsId)
      val submitAllinvalidSetup = Setup(prefix, invalidCmd, obsId)

      //#submitAll
      val submitAllF = async {
        await(assemblyCmdService.submitAll(List(submitAllSetup1, submitAllSetup2, submitAllinvalidSetup)))
      }
      val submitAllResponse = Await.result(submitAllF, timeout.duration)
      submitAllResponse.length shouldBe 3
      submitAllResponse(0) shouldBe a[Completed]
      submitAllResponse(1) shouldBe a[CompletedWithResult]
      submitAllResponse(2) shouldBe a[Invalid]
      //#submitAll

      //#submitAllInvalid
      val submitAllF2 = async {
        await(assemblyCmdService.submitAll(List(submitAllSetup1, submitAllinvalidSetup, submitAllSetup2)))
      }
      val submitAllResponse2 = Await.result(submitAllF2, timeout.duration)
      submitAllResponse2.length shouldBe 2
      submitAllResponse2(0) shouldBe a[Completed]
      submitAllResponse2(1) shouldBe a[Invalid]
      //#submitAllInvalid

      //#subscribeCurrentState
      // Subscriber code
      val expectedEncoderValue = 234
      val currStateSetup       = Setup(prefix, hcdCurrentStateCmd, obsId).add(encoder.set(expectedEncoderValue))
      // Setup a callback response to CurrentState
      var cstate: CurrentState = CurrentState(prefix, StateName("no cstate"), Set.empty)
      val subscription         = hcdCmdService.subscribeCurrentState(cs => cstate = cs)
      // Send a oneway to the HCD that will cause it to publish a CurrentState with the encoder value
      // in the command parameter "encoder". Callback will store value into cstate.
      hcdCmdService.oneway(currStateSetup)

      // Wait for a bit for callback
      Thread.sleep(100)
      // Test to see if value was received
      cstate(encoder).head shouldBe expectedEncoderValue

      // Unsubscribe to CurrentState
      subscription.unsubscribe()
      //#subscribeCurrentState


      // DEOPSCSW-229: Provide matchers infrastructure for comparison
      // DEOPSCSW-317: Use state values of HCD to determine command completion
      // long running command which uses matcher
      //#matcher
      val param: Parameter[Int] = encoder.set(100)
      val setupWithMatcher      = Setup(prefix, matcherCmd, obsId)

      // create a StateMatcher which specifies the desired algorithm and state to be matched.
      val demandMatcher: StateMatcher =
        DemandMatcher(DemandState(prefix, StateName("testStateName")).add(param), withUnits = false, timeout)

      // create matcher instance
      val matcher = new Matcher(assemblyLocation.componentRef, demandMatcher)

      // start the matcher so that it is ready to receive state published by the source
      val matcherResponseF: Future[MatcherResponse] = matcher.start

      // Submit command as a oneway and if the command is successfully validated,
      // check for matching of demand state against current state
      val matchResponseF: Future[MatchingResponse] = async {
        val onewayResponse: OnewayResponse = await(assemblyCmdService.oneway(setupWithMatcher))
        onewayResponse match {
          case Accepted(runId) ⇒
            val matcherResponse = await(matcherResponseF)
            // create appropriate response if demand state was matched from among the published state or otherwise
            // this would allow the response to be used to complete a command received by the Assembly
            matcherResponse match {
              case MatchCompleted =>
                Completed(onewayResponse.runId)
              case mf: MatchFailed =>
                Error(onewayResponse.runId, mf.throwable.getMessage)
            }
          case invalid: Invalid ⇒
            matcher.stop()
            invalid
          case locked: Locked =>
            matcher.stop()
            locked
        }
      }

      val commandResponse = Await.result(matchResponseF, timeout.duration)
      commandResponse shouldBe a[Completed] // (setupWithMatcher.runId)
      //#matcher

      //#onewayAndMatch
      val onewayMatchF = async {
        await(assemblyCmdService.onewayAndMatch(setupWithMatcher, demandMatcher)) match {
          case i: Invalid =>
            // Command was not accepted
            log.error(s"Oneway match was not accepted: ${i.issue}")
            i
          case c: Completed =>
            // Do some completed work
            c
          case e: Error =>
            // Match failed and timedout generating an error - log a message
            println("Error")
            log.error(s"Oeway match produced an error: ${e.message}")
            e
          case l: Locked =>
            // Destination component was locked, log a message
            log.error(s"Destination component was locked")
            l
        }
      }
      Await.result(onewayMatchF, timeout.duration) shouldBe a[Completed] // (setupWithMatcher.runId)
      //#onewayAndMatch

      // Test failed matching
      //#onewayMatchFail
      val setupWithFailedMatcher = Setup(prefix, matcherFailedCmd, obsId)
      val failedMatcher          = new Matcher(assemblyLocation.componentRef, demandMatcher)

      val failedMatcherResponseF: Future[MatcherResponse] = failedMatcher.start

      val eventualCommandResponse2: Future[MatchingResponse] = async {
        val initialResponse = await(assemblyCmdService.oneway(setupWithFailedMatcher))
        initialResponse match {
          case _: Accepted ⇒
            val matcherResponse = await(failedMatcherResponseF)
            // create appropriate response if demand state was matched from among the published state or otherwise
            matcherResponse match {
              case MatchCompleted  ⇒ Completed(initialResponse.runId)
              case MatchFailed(ex) ⇒ Error(initialResponse.runId, ex.getMessage)
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
      //commandResponse2.runId shouldBe setupWithFailedMatcher.runId

      // DEOPSCSW-233: Hide implementation by having a CCS API
      // DEOPSCSW-317: Use state values of HCD to determine command completion
      // simulate a scenario where timeout occurs while matching demand state vs current state
      // 1. Demand matcher expect matching to be done in 500 millis
      // 2. Assembly on receiving setupWithTimeoutMatcher command, sleeps for 1 second
      // 3. This results in Timeout in Matcher
      val demandMatcherToSimulateTimeout =
        DemandMatcher(DemandState(prefix, StateName("testStateName"), Set(param)), withUnits = false, 500.millis)
      val setupWithTimeoutMatcher = Setup(prefix, matcherTimeoutCmd, obsId)
      val matcherForTimeout       = new Matcher(assemblyLocation.componentRef, demandMatcherToSimulateTimeout)

      val matcherResponseF1: Future[MatcherResponse] = matcherForTimeout.start

      val timeoutExMsg = "The stream has not been completed in 500 milliseconds."
      val eventualCommandResponse1: Future[MatchingResponse] = async {
        val initialResponse = await(assemblyCmdService.oneway(setupWithTimeoutMatcher))
        initialResponse match {
          case _: Accepted ⇒
            val matcherResponse = await(matcherResponseF1)
            matcherResponse match {
              case MatchCompleted                                       ⇒ Completed(initialResponse.runId)
              case MatchFailed(ex) if ex.isInstanceOf[TimeoutException] ⇒ Error(initialResponse.runId, timeoutExMsg)
              case MatchFailed(ex)                                      ⇒ Error(initialResponse.runId, ex.getMessage)
            }
          case other @ (Invalid(_, _) | Locked(_)) =>
            matcher.stop()
            other.asInstanceOf[MatchingResponse]
        }
      }

      val commandResponseOnTimeout: MatchingResponse = Await.result(eventualCommandResponse1, timeout.duration)
      commandResponseOnTimeout shouldBe a[Error]
      commandResponseOnTimeout.asInstanceOf[Error].message shouldBe timeoutExMsg

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
      submitResponseProbe.expectMessageType[Completed](5.seconds) //, Completed(assemblySetup.runId))

      // send command with lock token and expect command processing response with result
      val assemblySetup2 = Setup(prefix, immediateResCmd, obsId)
      assemblyLocation.componentRef ! Submit(assemblySetup2, submitResponseProbe.ref)
      submitResponseProbe.expectMessageType[CompletedWithResult](5.seconds)

      enterBarrier("command-when-locked")
    }

    runOn(member2) {
      // spawn container having assembly and hcd running in jvm-3
      val wiring        = FrameworkWiring.make(system, locationService, mock[RedisClient])
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
