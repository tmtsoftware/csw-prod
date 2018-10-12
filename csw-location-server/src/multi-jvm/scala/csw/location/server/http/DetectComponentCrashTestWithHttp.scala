package csw.location.server.http

import csw.location.DetectComponentCrashTest

class DetectComponentCrashTestWithHttpMultiJvmNode1 extends DetectComponentCrashTestWithHttp(0, "http")
class DetectComponentCrashTestWithHttpMultiJvmNode2 extends DetectComponentCrashTestWithHttp(0, "http")
class DetectComponentCrashTestWithHttpMultiJvmNode3 extends DetectComponentCrashTestWithHttp(0, "http")

// DEOPSCSW-429: [SPIKE] Provide HTTP server and client for location service
class DetectComponentCrashTestWithHttp(ignore: Int, mode: String)
    extends DetectComponentCrashTest(ignore, mode)
    with HTTPLocationService
