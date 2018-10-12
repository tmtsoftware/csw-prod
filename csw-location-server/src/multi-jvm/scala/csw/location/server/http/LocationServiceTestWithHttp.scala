package csw.location.server.http

import csw.location.LocationServiceTest

class LocationServiceTestWithHttpMultiJvmNode1 extends LocationServiceTestWithHttp(0, "http")
class LocationServiceTestWithHttpMultiJvmNode2 extends LocationServiceTestWithHttp(0, "http")

// DEOPSCSW-429: [SPIKE] Provide HTTP server and client for location service
class LocationServiceTestWithHttp(ignore: Int, mode: String) extends LocationServiceTest(ignore, mode) with HTTPLocationService
