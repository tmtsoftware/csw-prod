package csw.common

import akka.actor.typed.ActorRef
import akka.actor.testkit.typed.scaladsl.TestProbe
import com.persist.JsonOps.JsonObject
import csw.command.models.framework.{ContainerLifecycleState, SupervisorLifecycleState}
import csw.command.messages.ComponentCommonMessage.GetSupervisorLifecycleState
import csw.command.messages.{ComponentMessage, ContainerMessage}
import csw.command.messages.ContainerCommonMessage.GetContainerLifecycleState
import csw.location.server.commons.BlockingUtils
import csw.logging.internal.LoggingLevels.Level
import org.scalatest.Matchers

import scala.collection.mutable
import scala.concurrent.duration.Duration

object FrameworkAssertions extends Matchers {

  def assertThatContainerIsRunning(
      containerRef: ActorRef[ContainerMessage],
      probe: TestProbe[ContainerLifecycleState],
      duration: Duration
  ): Unit = {
    def getContainerLifecycleState: ContainerLifecycleState = {
      containerRef ! GetContainerLifecycleState(probe.ref)
      probe.expectMessageType[ContainerLifecycleState]
    }

    assert(
      BlockingUtils.poll(getContainerLifecycleState == ContainerLifecycleState.Running, duration),
      s"expected :${ContainerLifecycleState.Running}, found :$getContainerLifecycleState"
    )
  }

  def assertThatSupervisorIsRunning(
      actorRef: ActorRef[ComponentMessage],
      probe: TestProbe[SupervisorLifecycleState],
      duration: Duration
  ): Unit = {
    def getSupervisorLifecycleState: SupervisorLifecycleState = {
      actorRef ! GetSupervisorLifecycleState(probe.ref)
      probe.expectMessageType[SupervisorLifecycleState]
    }

    assert(
      BlockingUtils.poll(getSupervisorLifecycleState == SupervisorLifecycleState.Running, duration),
      s"expected :${SupervisorLifecycleState.Running}, found :$getSupervisorLifecycleState"
    )
  }

  def assertThatMessageIsLogged(
      logBuffer: mutable.Buffer[JsonObject],
      componentName: String,
      message: String,
      expLevel: Level,
      className: String
  ): Unit = {

    val maybeLogMsg = findLogMessage(logBuffer, message)

    assert(maybeLogMsg.isDefined, s"$message not found in $logBuffer")
    val logMsg = maybeLogMsg.get
    Level(logMsg("@severity").toString) shouldBe expLevel
    logMsg("@componentName") shouldBe componentName
    logMsg("class") shouldBe sanitizeClassName(className)
  }

  def assertThatExceptionIsLogged(
      logBuffer: mutable.Buffer[JsonObject],
      componentName: String,
      message: String,
      expLevel: Level,
      className: String,
      exceptionClassName: String,
      exceptionMessage: String
  ): Unit = {

    val maybeLogMsg = findLogMessage(logBuffer, message)

    assert(maybeLogMsg.isDefined, s"$message not found in $logBuffer")
    val logMsg = maybeLogMsg.get
    Level(logMsg("@severity").toString) shouldBe expLevel
    logMsg("@componentName") shouldBe componentName
    logMsg("class") shouldBe sanitizeClassName(className)

    logMsg.contains("trace") shouldBe true
    val traceBlock    = logMsg("trace").asInstanceOf[JsonObject]
    val traceMsgBlock = traceBlock("message").asInstanceOf[JsonObject]
    traceMsgBlock("ex") shouldBe s"class ${sanitizeClassName(exceptionClassName)}"
    traceMsgBlock("message") shouldBe exceptionMessage
  }

  def assertThatExceptionIsNotLogged(
      logBuffer: mutable.Buffer[JsonObject],
      message: String,
  ): Unit = {
    val maybeLogMsg = findLogMessageSubString(logBuffer, message)
    assert(maybeLogMsg.isEmpty, s"$message found in $logBuffer")
  }

  private def findLogMessage(logBuffer: mutable.Buffer[JsonObject], message: String): Option[JsonObject] =
    logBuffer.find(_.exists {
      case (_, `message`) ⇒ true
      case (_, _)         ⇒ false
    })

  private def findLogMessageSubString(logBuffer: mutable.Buffer[JsonObject], message: String): Option[JsonObject] =
    logBuffer.find(_.exists {
      case (_, actualMessage: String) if actualMessage.startsWith(message) ⇒ true
      case (_, _)                                                          ⇒ false
    })

  private def sanitizeClassName(className: String): String =
    if (className.endsWith("$")) className.dropRight(1) else className
}
