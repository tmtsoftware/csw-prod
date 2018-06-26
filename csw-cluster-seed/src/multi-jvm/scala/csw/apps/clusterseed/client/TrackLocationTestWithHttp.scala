package csw.apps.clusterseed.client

import csw.services.location.TrackLocationTest

class TrackLocationTestWithHttpMultiJvmNode1 extends TrackLocationTestWithHttp(0)
class TrackLocationTestWithHttpMultiJvmNode2 extends TrackLocationTestWithHttp(0)
class TrackLocationTestWithHttpMultiJvmNode3 extends TrackLocationTestWithHttp(0)

// DEOPSCSW-429: [SPIKE] Provide HTTP server and client for location service
class TrackLocationTestWithHttp(ignore: Int) extends TrackLocationTest(0, "http") with HTTPLocationService
