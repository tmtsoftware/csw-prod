package csw.location.server.commons

import com.typesafe.config.ConfigException
import csw.location.api.commons.{ClusterSettings, Constants}
import csw.location.api.internal.Networks
import org.jboss.netty.logging.{InternalLoggerFactory, Slf4JLoggerFactory}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FunSuite, Matchers}

class ClusterSettingsTest extends FunSuite with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  // Fix to avoid 'java.util.concurrent.RejectedExecutionException: Worker has already been shutdown'
  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)

  override protected def afterAll(): Unit = {
    System.clearProperty("clusterPort")
    System.clearProperty("clusterSeeds")
    System.clearProperty("interfaceName")
  }

  test("exception is thrown when settings are not found for a given cluster name") {
    val settings: ClusterSettings = ClusterSettings("undefined-settings-in-conf")
    a[ConfigException.Missing] shouldBe thrownBy(settings.config)
  }

  test("default cluster settings are used when no custom parameters are supplied") {
    val clusterSettings = ClusterSettings()
    val config          = clusterSettings.config

    clusterSettings.clusterName shouldBe Constants.ClusterName
    config.getString("akka.remote.netty.tcp.hostname") shouldBe new Networks().hostname()
    config.getInt("akka.remote.netty.tcp.port") shouldBe 0
    config.getList("akka.cluster.seed-nodes").size shouldBe 0
  }

  test("cluster settings with custom parameters are used") {
    val port = 8888
    val clusterSettings: ClusterSettings =
      ClusterSettings().withInterface("en0").onPort(port).joinSeeds("10.10.10.10, 10.10.10.11")

    clusterSettings.interfaceName shouldBe "en0"
    clusterSettings.port shouldBe port
    clusterSettings.seedNodes shouldBe List("10.10.10.10", "10.10.10.11").map { hostname =>
      s"akka.tcp://${clusterSettings.clusterName}@$hostname"
    }
  }

  test("cluster settings with join Local") {
    val port     = 9010
    val portList = List(9000, 9001, 9002, 9003)
    val hostname = new Networks().hostname
    val clusterSettings: ClusterSettings =
      ClusterSettings().onPort(port).joinLocal(portList(0), portList(1), portList(2), portList(3))

    clusterSettings.port shouldBe port
    clusterSettings.seedNodes shouldBe portList.map { port =>
      s"akka.tcp://${clusterSettings.clusterName}@$hostname:$port"
    }
  }

  test("cluster settings with custom values") {
    val port                             = 9001
    val ipList                           = List("10.10.10.10", "10.10.10.11", "10.10.10.12")
    val values                           = Map("interfaceName" -> "en0", "clusterSeeds" -> ipList.mkString(", "), "clusterPort" -> "9000")
    val clusterSettings: ClusterSettings = ClusterSettings(values = values).onPort(port)

    clusterSettings.interfaceName shouldBe "en0"
    clusterSettings.port shouldBe port
    clusterSettings.seedNodes shouldBe ipList.map { hostname =>
      s"akka.tcp://${clusterSettings.clusterName}@$hostname"
    }
  }

  test("cluster settings with system properties") {
    val systemPort          = 9002
    val systemSeeds         = "10.10.10.12, 10.10.10.13"
    val systemInterfacename = "eth0"

    System.setProperty("clusterPort", systemPort.toString)
    System.setProperty("clusterSeeds", systemSeeds.toString)
    System.setProperty("interfaceName", systemInterfacename)

    val clusterSettings = ClusterSettings()

    clusterSettings.port shouldBe systemPort
    clusterSettings.interfaceName shouldBe systemInterfacename
    clusterSettings.seedNodes shouldBe List("10.10.10.12", "10.10.10.13").map { hostname =>
      s"akka.tcp://${clusterSettings.clusterName}@$hostname"
    }
  }
}
