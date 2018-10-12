package csw.config.client

import java.nio.file.Paths

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import csw.config.api.models.ConfigData
import csw.config.client.helpers.OneClientAndServer
import csw.config.client.internal.ActorRuntime
import csw.config.client.scaladsl.ConfigClientFactory
import csw.config.server.commons.TestFileUtils
import csw.config.server.{ServerWiring, Settings}
import csw.location.helpers.LSNodeSpec
import csw.location.server.http.HTTPLocationService

class ConfigServiceTestMultiJvmNode1 extends ConfigServiceTest(0)
class ConfigServiceTestMultiJvmNode2 extends ConfigServiceTest(0)

class ConfigServiceTest(ignore: Int) extends LSNodeSpec(config = new OneClientAndServer, mode = "http") with HTTPLocationService {

  import config._

  private val testFileUtils = new TestFileUtils(new Settings(ConfigFactory.load()))

  override def afterAll(): Unit = {
    super.afterAll()
    testFileUtils.deleteServerFiles()
  }

  test("should start config service server on one node and client should able to create and get files from other node") {

    runOn(server) {
      val serverWiring = ServerWiring.make(locationService)
      serverWiring.svnRepo.initSvnRepo()
      serverWiring.httpService.registeredLazyBinding.await
      enterBarrier("server-started")
      enterBarrier("end")
    }

    runOn(client) {
      enterBarrier("server-started")
      val actorRuntime = new ActorRuntime(ActorSystem())
      import actorRuntime._
      val configService = ConfigClientFactory.adminApi(actorSystem, locationService)

      val configValue: String =
        """
          |axisName1 = tromboneAxis1
          |axisName2 = tromboneAxis2
          |axisName3 = tromboneAxis3
          |""".stripMargin

      val file = Paths.get("test.conf")
      configService.create(file, ConfigData.fromString(configValue), annex = false, "commit test file").await
      val actualConfigValue = configService.getLatest(file).await.get.toStringF.await
      actualConfigValue shouldBe configValue
      enterBarrier("end")
    }
  }
}
