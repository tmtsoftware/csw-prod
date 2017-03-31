package csw.services.location.helpers

import akka.remote.testkit.{MultiNodeSpec, MultiNodeSpecCallbacks}
import akka.testkit.ImplicitSender
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike, Matchers}

abstract class LSThreeNodeSpec(val config: LSThreeNodeConfig) extends MultiNodeSpec(config, config.makeSystem)
  with ImplicitSender
  with MultiNodeSpecCallbacks
  with FunSuiteLike
  with Matchers
  with BeforeAndAfterAll {

  override def initialParticipants: Int = roles.size

  override def beforeAll(): Unit = multiNodeSpecBeforeAll()

  override def afterAll(): Unit = multiNodeSpecAfterAll()
}
