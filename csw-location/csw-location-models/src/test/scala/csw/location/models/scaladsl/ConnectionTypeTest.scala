package csw.location.models.scaladsl

import csw.location.models.ConnectionType
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FunSuite, Matchers}

class ConnectionTypeTest extends FunSuite with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  // DEOPSCSW-14: Codec for data model
  test("ConnectionType should be any one of this types : 'http', 'tcp' and 'akka'") {

    val expectedConnectionTypeValues = Set("http", "tcp", "akka")

    val actualConnectionTypeValues: Set[String] =
      ConnectionType.values.map(connectionType => connectionType.entryName).toSet

    actualConnectionTypeValues shouldEqual expectedConnectionTypeValues
  }

}