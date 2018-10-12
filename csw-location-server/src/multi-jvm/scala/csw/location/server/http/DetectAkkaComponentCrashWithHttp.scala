package csw.location.server.http

import csw.location.DetectAkkaComponentCrashTest

class DetectAkkaComponentCrashWithHttpMultiJvmNode1 extends DetectAkkaComponentCrashWithHttp(0, "http")
class DetectAkkaComponentCrashWithHttpMultiJvmNode2 extends DetectAkkaComponentCrashWithHttp(0, "http")
class DetectAkkaComponentCrashWithHttpMultiJvmNode3 extends DetectAkkaComponentCrashWithHttp(0, "http")

// DEOPSCSW-429: [SPIKE] Provide HTTP server and client for location service
class DetectAkkaComponentCrashWithHttp(ignore: Int, mode: String)
    extends DetectAkkaComponentCrashTest(ignore, mode)
    with HTTPLocationService
