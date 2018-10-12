package csw.config

import java.nio.file.{Files, Paths}

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import csw.config.api.models.ConfigData
import csw.config.cli.ClientCliWiring
import csw.config.client.internal.ActorRuntime
import csw.config.client.scaladsl.ConfigClientFactory
import csw.config.helpers.TwoClientsAndServer
import csw.config.server.commons.TestFileUtils
import csw.config.server.{ServerWiring, Settings}
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.location.helpers.LSNodeSpec
import csw.location.server.http.HTTPLocationService
import org.scalatest.FunSuiteLike

class ConfigCliAppTestMultiJvmNode1 extends ConfigCliAppTest(0)
class ConfigCliAppTestMultiJvmNode2 extends ConfigCliAppTest(0)
class ConfigCliAppTestMultiJvmNode3 extends ConfigCliAppTest(0)

// DEOPSCSW-43: Access Configuration service from any CSW component
class ConfigCliAppTest(ignore: Int)
    extends LSNodeSpec(config = new TwoClientsAndServer, mode = "http")
    with HTTPLocationService
    with FunSuiteLike {

  import config._

  private val testFileUtils = new TestFileUtils(new Settings(ConfigFactory.load()))

  override def afterAll(): Unit = {
    super.afterAll()
    testFileUtils.deleteServerFiles()
  }

  val inputFilePath            = getClass.getResource("/tromboneHCD.conf").getPath
  val updatedInputFilePath     = getClass.getResource("/tromboneAssembly.conf").getPath
  val inputFileContents        = scala.io.Source.fromFile(inputFilePath).mkString
  val updatedInputFileContents = scala.io.Source.fromFile(updatedInputFilePath).mkString
  val repoPath1                = "/client1/hcd/text/tromboneHCD.conf"
  val repoPath2                = "/client2/hcd/text/app.conf"
  val comment                  = "test comment"

  test("should upload, update, get and set active version of configuration files") {
    runOn(server) {
      // Start server on first node
      val serverWiring = ServerWiring.make(locationService)
      serverWiring.svnRepo.initSvnRepo()
      serverWiring.httpService.registeredLazyBinding.await
      enterBarrier("server-started")
      enterBarrier("client1-create")
      enterBarrier("client2-create-pass")
      enterBarrier("client1-update")
      enterBarrier("client2-update-pass")
      enterBarrier("client1-setActive")
      enterBarrier("client2-create")
    }

    // config client command line app is exercised on client1
    runOn(client1) {
      enterBarrier("server-started")
      implicit val system: ActorSystem    = ActorSystem()
      implicit val mat: ActorMaterializer = ActorMaterializer()

      def cliApp() = ClientCliWiring.noPrinting(system, HttpLocationServiceFactory.makeLocalClient).cliApp

      cliApp().start("csw-config-cli", Array("create", repoPath1, "-i", inputFilePath, "-c", comment))
      enterBarrier("client1-create")
      enterBarrier("client2-create-pass")

      cliApp().start("csw-config-cli", Array("update", repoPath1, "-i", updatedInputFilePath, "-c", comment))
      enterBarrier("client1-update")
      enterBarrier("client2-update-pass")

      cliApp().start("csw-config-cli", Array("setActiveVersion", repoPath1, "--id", "1", "-c", comment))
      enterBarrier("client1-setActive")

      // Verify that client1 (cli app) is able to access file created by client2
      enterBarrier("client2-create")
      val tempOutputFile = Files.createTempFile("output", ".conf").toString
      cliApp().start("csw-config-cli", Array("get", repoPath2, "-o", tempOutputFile))
      new String(Files.readAllBytes(Paths.get(tempOutputFile))) shouldEqual inputFileContents

    }

    // config client admin api is exercised on client2
    runOn(client2) {
      enterBarrier("server-started")
      val actorRuntime = new ActorRuntime(system)
      import actorRuntime._
      val configService = ConfigClientFactory.adminApi(system, locationService)

      enterBarrier("client1-create")
      val actualConfigValue = configService.getLatest(Paths.get(repoPath1)).await.get.toStringF.await
      actualConfigValue shouldBe inputFileContents
      enterBarrier("client2-create-pass")

      enterBarrier("client1-update")
      val actualUpdatedConfigValue = configService.getLatest(Paths.get(repoPath1)).await.get.toStringF.await
      actualUpdatedConfigValue shouldBe updatedInputFileContents
      enterBarrier("client2-update-pass")

      enterBarrier("client1-setActive")
      val actualActiveConfigValue = configService.getActive(Paths.get(repoPath1)).await.get.toStringF.await
      actualActiveConfigValue shouldBe inputFileContents

      configService
        .create(Paths.get(repoPath2), ConfigData.fromString(inputFileContents), comment = "creating app.conf")
        .await
      enterBarrier("client2-create")
    }
    enterBarrier("after-1")
  }
}
