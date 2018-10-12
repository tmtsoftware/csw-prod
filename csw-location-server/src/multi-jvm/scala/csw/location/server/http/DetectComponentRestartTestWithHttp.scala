package csw.location.server.http

import csw.location.DetectComponentRestartTest

class DetectComponentRestartTestWithHttpMultiJvmNode1 extends DetectComponentRestartTestWithHttp(0, "http")
class DetectComponentRestartTestWithHttpMultiJvmNode2 extends DetectComponentRestartTestWithHttp(0, "http")
class DetectComponentRestartTestWithHttpMultiJvmNode3 extends DetectComponentRestartTestWithHttp(0, "http")

// DEOPSCSW-429: [SPIKE] Provide HTTP server and client for location service
class DetectComponentRestartTestWithHttp(ignore: Int, mode: String)
    extends DetectComponentRestartTest(ignore, mode)
    with HTTPLocationService {

  override def afterAll(): Unit = super.afterAll()

}
