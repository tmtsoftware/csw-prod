package csw.framework.internal.supervisor

import akka.Done
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior, PostStop}
import akka.pattern.StatusReply
import csw.command.client.models.framework.LockingResponse
import csw.logging.client.scaladsl.LoggerFactory
import csw.prefix.models.{Prefix, Subsystem}

import scala.concurrent.duration.FiniteDuration

object LockManager2 {
  val AdminPrefix:Prefix = Prefix(s"${Subsystem.CSW}.admin")

  private val LockNotificationKey = "lockNotification"
  private val LockExpirationKey   = "lockExpiration"

  trait LockManager2Message

  final case class LockComponent(
      source: Prefix,
      replyTo: ActorRef[LockingResponse],
      svrReplyTo: ActorRef[LockManager2Response],
      leaseDuration: FiniteDuration
  ) extends LockManager2Message

  final case class UnlockComponent(source: Prefix, replyTo: ActorRef[LockingResponse], svrReplyTo: ActorRef[LockManager2Response])
      extends LockManager2Message

  final case class IsLocked(replyTo: ActorRef[LockManager2Response]) extends LockManager2Message

  final case class IsCommandPrefixAllowed(cmdPrefix: Prefix, replyTo: ActorRef[StatusReply[Done]]) extends LockManager2Message

  private final case class LockTimeOut(replyTo: ActorRef[LockingResponse]) extends LockManager2Message

  private final case class LockAboutToTimeout(replyTo: ActorRef[LockingResponse]) extends LockManager2Message

  trait LockManager2Response
  final case class Locked(prefix: Prefix) extends LockManager2Response
  final case object Unlocked extends LockManager2Response

  def apply(loggerFactory: LoggerFactory): Behavior[LockManager2Message] = unlocked(loggerFactory)

  private def unlocked(loggerFactory: LoggerFactory): Behavior[LockManager2Message] = {
    val log = loggerFactory.getLogger

    Behaviors.withTimers { timerScheduler =>
      Behaviors.receiveMessagePartial {
        case LockComponent(lockPrefix, replyTo, svrReplyTo, leaseDuration) =>
          log.info(s"The lock is successfully acquired by component: $lockPrefix")
          startTimerAndReply(timerScheduler, leaseDuration, replyTo)

          // Send Locked to supervisor for updating internal state
          svrReplyTo ! LockManager2.Locked(lockPrefix)
          locked(loggerFactory, timerScheduler, lockPrefix, svrReplyTo)
        case UnlockComponent(source, replyTo, _) =>
          // This shouldn't happen since super will be in locked state to get Unlocked, super refuses UnlockComponent
          // The replyTo is required to behave like original LockManager: DEOPSCSW-222, DEOPSCSW-301
          log.error(s"LockManager received an UnlockComponent from: $source while unlocked -- ignoring.")
          replyTo ! LockingResponse.LockReleased
          Behaviors.unhandled
        case IsLocked(replyTo) =>
          replyTo ! Unlocked
          Behaviors.same
      }
    }
  }

  private def locked(
      loggerFactory: LoggerFactory,
      timerScheduler: TimerScheduler[LockManager2Message],
      lockPrefix: Prefix,
      svrReplyTo: ActorRef[LockManager2Response]
  ): Behavior[LockManager2Message] = {
    val log = loggerFactory.getLogger

    Behaviors
      .receiveMessage[LockManager2Message] {
        case IsLocked(replyTo) =>
          replyTo ! Locked(lockPrefix)
          Behaviors.same
        case LockAboutToTimeout(replyTo) =>
          replyTo ! LockingResponse.LockExpiringShortly
          Behaviors.same
        case LockTimeOut(replyTo) =>
          replyTo ! LockingResponse.LockExpired
          // Timeout means supervisor should go back to running state
          svrReplyTo ! Unlocked
          Behaviors.stopped
        case LockComponent(source, replyTo, _, _) if source != lockPrefix =>
          // This is the case where the locking component is not the same as the original locking component
          val failureReason =
            s"Invalid source $source for acquiring lock. Lock is currently owned by component: $lockPrefix."
          log.error(failureReason)
          // Client receives AcquireLockFailed
          replyTo ! LockingResponse.AcquiringLockFailed(failureReason)
          Behaviors.same
        case LockComponent(_, replyTo, _, leaseDuration) =>
          // Reacquire lock with current prefix, this re-ups the timer
          startTimerAndReply(timerScheduler, leaseDuration, replyTo)
          // No message to the supervisor here
          locked(loggerFactory, timerScheduler, lockPrefix, svrReplyTo)
        case UnlockComponent(unlockPrefix, replyTo, svrReplyTo) =>
          if (unlockPrefix == lockPrefix || unlockPrefix == AdminPrefix) {
            log.info(s"The lock is successfully released by component: $unlockPrefix")
            replyTo ! LockingResponse.LockReleased
            timerScheduler.cancel(LockNotificationKey)
            timerScheduler.cancel(LockExpirationKey)
            // Tell the supervisor that the lock was successfully released
            svrReplyTo ! Unlocked
            Behaviors.stopped
          }
          else {
            val failureReason =
              s"Invalid prefix $unlockPrefix for releasing lock. Currently the component is locked by: $lockPrefix."
            log.error(failureReason)
            replyTo ! LockingResponse.ReleasingLockFailed(failureReason)
            // No need to alert supervisor of this condition because component is still locked
            Behaviors.same
          }
        case IsCommandPrefixAllowed(unlockPrefix, replyTo) =>
          if (unlockPrefix == lockPrefix || unlockPrefix == AdminPrefix) {
            replyTo ! StatusReply.Ack
          }
          else {
            replyTo ! StatusReply.Error(s"Prefix: $unlockPrefix is not allowed.")
          }
          Behaviors.same
      }
      .receiveSignal {
        case (_: ActorContext[LockManager2Message], PostStop) =>
          log.debug(msg = s"PostStop signal received for lockManager: $lockPrefix.")
          Behaviors.same
      }
  }

  // Private def to reuse code fragment
  private def startTimerAndReply(timerScheduler: TimerScheduler[LockManager2Message],
                                 leaseDuration: FiniteDuration,
                                 replyTo: ActorRef[LockingResponse]): Unit = {
    timerScheduler.startSingleTimer(LockNotificationKey, LockAboutToTimeout(replyTo), leaseDuration - (leaseDuration / 10))
    timerScheduler.startSingleTimer(LockExpirationKey, LockTimeOut(replyTo), leaseDuration)
    // Send client LockAcquired, no need to update supervisor
    replyTo ! LockingResponse.LockAcquired
  }
}
