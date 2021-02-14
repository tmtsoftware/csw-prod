package csw.framework.internal.supervisor

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import csw.command.client.models.framework.LockingResponse
import csw.logging.api.scaladsl.Logger
import csw.logging.client.scaladsl.LoggerFactory
import csw.prefix.models.Prefix
import org.mockito.MockitoSugar
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationDouble


class LockManager2Test extends AnyFunSuite with MockitoSugar with BeforeAndAfterAll {
  import LockManager2._

  private val testKit = ActorTestKit()

  override def afterAll(): Unit = testKit.shutdownTestKit()
  private val prefix        = Prefix("wfos.eng.ui")
  private val invalidPrefix = Prefix("iris.eng.ui")
  private val longDuration = 5.seconds

  private val mockedLoggerFactory      = mock[LoggerFactory]
  private val mockedLogger             = mock[Logger]
  when(mockedLoggerFactory.getLogger).thenReturn(mockedLogger)

  test("should be unlocked when prefix is not available | DEOPSCSW-222, DEOPSCSW-301") {
    val isLockedProbe = testKit.createTestProbe[LockManager2Response]
    val lockManager2ResponseProbe = testKit.createTestProbe[LockManager2Response]

    val lm = testKit.spawn(LockManager2(mockedLoggerFactory))
    lm ! IsLocked(isLockedProbe.ref)

    isLockedProbe.expectMessage(Unlocked)

    // Check for unhandled
    lm ! IsLocked(lockManager2ResponseProbe.ref)
    lockManager2ResponseProbe.expectMessage(Unlocked)
  }

  test("should be locked when prefix is available | DEOPSCSW-222, DEOPSCSW-301") {
    val lockingResponseProbe = testKit.createTestProbe[LockingResponse]
    val lockManager2ResponseProbe = testKit.createTestProbe[LockManager2Response]

    // Create unlocked and check
    val lm = testKit.spawn(LockManager2(mockedLoggerFactory))
    lm ! IsLocked(lockManager2ResponseProbe.ref)
    lockManager2ResponseProbe.expectMessage(Unlocked)

    // Lock with prefix, acquire sent to client, locked sent to supervisor
    lm ! LockComponent(prefix, lockingResponseProbe.ref, lockManager2ResponseProbe. ref, longDuration)
    lockingResponseProbe.expectMessage(LockingResponse.LockAcquired)
    lockManager2ResponseProbe.expectMessage(Locked(prefix))

    // Is locked?
    lm ! IsLocked(lockManager2ResponseProbe.ref)
    lockManager2ResponseProbe.expectMessage(Locked(prefix))
  }

  test("lockPrefix should able to reacquire lock | DEOPSCSW-222, DEOPSCSW-301") {
    val lockingResponseProbe = testKit.createTestProbe[LockingResponse]
    val lockManager2ResponseProbe = testKit.createTestProbe[LockManager2Response]

    // Create unlocked
    val lm = testKit.spawn(LockManager2(mockedLoggerFactory))

    lm ! IsLocked(lockManager2ResponseProbe.ref)
    lockManager2ResponseProbe.expectMessage(Unlocked)

    // Lock with prefix
    lm ! LockComponent(prefix, lockingResponseProbe.ref, lockManager2ResponseProbe. ref, longDuration)
    lockingResponseProbe.expectMessage(LockingResponse.LockAcquired)

    // Supervisor receives Locked
    lockManager2ResponseProbe.expectMessage(Locked(prefix))

    lm ! IsLocked(lockManager2ResponseProbe.ref)
    lockManager2ResponseProbe.expectMessage(Locked(prefix))

    // Try locking again with same prefix
    lm ! LockComponent(prefix, lockingResponseProbe.ref, lockManager2ResponseProbe. ref, longDuration)
    lockingResponseProbe.expectMessage(LockingResponse.LockAcquired)
    // No message needed for supervisor
    lockManager2ResponseProbe.expectNoMessage()
  }

  test("should not acquire lock when invalid prefix is provided | DEOPSCSW-222, DEOPSCSW-301") {
    val lockingResponseProbe = testKit.createTestProbe[LockingResponse]
    val lockManager2ResponseProbe = testKit.createTestProbe[LockManager2Response]

    val lm = testKit.spawn(LockManager2(mockedLoggerFactory))

    // First lock with good prefix
    lm ! LockComponent(prefix, lockingResponseProbe.ref, lockManager2ResponseProbe.ref, longDuration)
    lockingResponseProbe.expectMessage(LockingResponse.LockAcquired)
    // Supervisor receives Locked
    lockManager2ResponseProbe.expectMessage(Locked(prefix))

    lm ! IsLocked(lockManager2ResponseProbe.ref)
    lockManager2ResponseProbe.expectMessage(Locked(prefix))

    // Try with a different prefix - no response to supervisor
    lm ! LockComponent(invalidPrefix, lockingResponseProbe.ref, lockManager2ResponseProbe.ref, longDuration)
    lockManager2ResponseProbe.expectNoMessage()

    // Is still locked?
    lm ! IsLocked(lockManager2ResponseProbe.ref)
    lockManager2ResponseProbe.expectMessage(Locked(prefix))
  }

  test("should be able to unlock | DEOPSCSW-222, DEOPSCSW-301") {
    val lockingResponseProbe = testKit.createTestProbe[LockingResponse]
    val lockManager2ResponseProbe = testKit.createTestProbe[LockManager2Response]

    val lm = testKit.spawn(LockManager2(mockedLoggerFactory))

    lm ! LockComponent(prefix, lockingResponseProbe.ref, lockManager2ResponseProbe.ref, longDuration)
    lockingResponseProbe.expectMessage(LockingResponse.LockAcquired)
    // Supervisor receives Locked
    lockManager2ResponseProbe.expectMessage(Locked(prefix))

    lm ! IsLocked(lockManager2ResponseProbe.ref)
    lockManager2ResponseProbe.expectMessage(Locked(prefix))

    lm ! UnlockComponent(prefix, lockingResponseProbe.ref, lockManager2ResponseProbe.ref)
    lockingResponseProbe.expectMessage(LockingResponse.LockReleased)

    // Super gets a response for this since it may fail
    lockManager2ResponseProbe.expectMessage(LockManager2.Unlocked)

    // Is still locked?
    // How to watch from testkit for terminated?
  }

  test("should not be able to unlock with invalid prefix | DEOPSCSW-222, DEOPSCSW-301") {
    val lockingResponseProbe = testKit.createTestProbe[LockingResponse]
    val lockManager2ResponseProbe = testKit.createTestProbe[LockManager2Response]

    val lm = testKit.spawn(LockManager2(mockedLoggerFactory))

    lm ! LockComponent(prefix, lockingResponseProbe.ref, lockManager2ResponseProbe.ref, longDuration)
    lockingResponseProbe.expectMessage(LockingResponse.LockAcquired)
    // Supervisor receives Locked
    lockManager2ResponseProbe.expectMessage(Locked(prefix))

    lm ! UnlockComponent(invalidPrefix, lockingResponseProbe.ref, lockManager2ResponseProbe.ref)
    lockingResponseProbe.expectMessageType[LockingResponse.ReleasingLockFailed]

    // Is it still locked by original locker?
    lm ! IsLocked(lockManager2ResponseProbe.ref)
    lockManager2ResponseProbe.expectMessage(Locked(prefix))
  }

  test("should not result in failure when tried to unlock already unlocked component | DEOPSCSW-222, DEOPSCSW-301") {
    val lockingResponseProbe = testKit.createTestProbe[LockingResponse]
    val lockManager2ResponseProbe = testKit.createTestProbe[LockManager2Response]

    val lm = testKit.spawn(LockManager2(mockedLoggerFactory))

    // This is the behavior of the original impl
    lm ! UnlockComponent(prefix, lockingResponseProbe.ref, lockManager2ResponseProbe.ref)
    lockingResponseProbe.expectMessage(LockingResponse.LockReleased)

    // Is it still unlocked
    lm ! IsLocked(lockManager2ResponseProbe.ref)
    lockManager2ResponseProbe.expectMessage(Unlocked)
  }

  test("should get correct protocol for timeouts") {
    val lockingResponseProbe = testKit.createTestProbe[LockingResponse]
    val lockManager2ResponseProbe = testKit.createTestProbe[LockManager2Response]

    val lm = testKit.spawn(LockManager2(mockedLoggerFactory))

    val testDuration = 2.seconds

    lm ! LockComponent(prefix, lockingResponseProbe.ref, lockManager2ResponseProbe.ref, testDuration)
    lockingResponseProbe.expectMessage(LockingResponse.LockAcquired)
    lockingResponseProbe.expectNoMessage(1700.milli)

    lockingResponseProbe.expectMessage(LockingResponse.LockExpiringShortly)
    lockingResponseProbe.expectNoMessage(100.milli)
    lockingResponseProbe.expectMessage(LockingResponse.LockExpired)
  }

  test("should re-lock and grow timeouts") {
    val lockingResponseProbe = testKit.createTestProbe[LockingResponse]
    val lockManager2ResponseProbe = testKit.createTestProbe[LockManager2Response]

    val lm = testKit.spawn(LockManager2(mockedLoggerFactory))

    val testDuration1 = 1.seconds
    val testDuration2 = 2.seconds

    lm ! LockComponent(prefix, lockingResponseProbe.ref, lockManager2ResponseProbe.ref, testDuration1)
    lockingResponseProbe.expectMessage(LockingResponse.LockAcquired)

    lockingResponseProbe.expectMessage(LockingResponse.LockExpiringShortly)
    // When lock is about to expire, redo lock with longer time
    lm ! LockComponent(prefix, lockingResponseProbe.ref, lockManager2ResponseProbe.ref, testDuration2)
    lockingResponseProbe.expectMessage(LockingResponse.LockAcquired)

    lockingResponseProbe.expectNoMessage(1500.milli)
    lockingResponseProbe.expectMessage(LockingResponse.LockExpiringShortly)
    lockingResponseProbe.expectMessage(LockingResponse.LockExpired)
  }

  // DEOPSCSW-302: Support Unlocking by Admin
  test("should allow unlocking any locked component by admin | DEOPSCSW-222, DEOPSCSW-301, DEOPSCSW-302") {
    val lockingResponseProbe = testKit.createTestProbe[LockingResponse]
    val lockManager2ResponseProbe = testKit.createTestProbe[LockManager2Response]

    val lm = testKit.spawn(LockManager2(mockedLoggerFactory))

    lm ! LockComponent(prefix, lockingResponseProbe.ref, lockManager2ResponseProbe.ref, longDuration)
    lockingResponseProbe.expectMessage(LockingResponse.LockAcquired)
    // Supervisor receives Locked
    lockManager2ResponseProbe.expectMessage(Locked(prefix))

    val adminPrefix    = LockManager2.AdminPrefix

    lm ! UnlockComponent(adminPrefix, lockingResponseProbe.ref, lockManager2ResponseProbe.ref)
    lockingResponseProbe.expectMessage(LockingResponse.LockReleased)

    lockManager2ResponseProbe.expectMessage(Unlocked)
  }

}
