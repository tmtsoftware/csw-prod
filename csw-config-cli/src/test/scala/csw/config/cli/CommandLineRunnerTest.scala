package csw.config.cli

import java.nio.file.{Files, Paths}
import java.time.Instant

import akka.actor.ActorSystem
import akka.actor.CoordinatedShutdown.UnknownReason
import akka.stream.ActorMaterializer
import csw.config.api.models.ConfigId
import csw.config.commons.TestFutureExtension.RichFuture
import csw.config.commons.{ArgsUtil, TestFileUtils}
import csw.config.server.ServerWiring
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.location.server.http.HTTPLocationService
import org.scalatest.{BeforeAndAfterEach, Matchers}

// DEOPSCSW-112: Command line interface client for Configuration service
// DEOPSCSW-43: Access Configuration service from any CSW component
class CommandLineRunnerTest extends HTTPLocationService with Matchers with BeforeAndAfterEach {

  private val serverSystem: ActorSystem    = ActorSystem("config-server")
  private val serverMat: ActorMaterializer = ActorMaterializer()(serverSystem)
  private val serverLocationService        = HttpLocationServiceFactory.makeLocalClient(serverSystem, serverMat)
  private val serverWiring                 = ServerWiring.make(serverSystem, serverLocationService)
  private val httpService                  = serverWiring.httpService
  httpService.registeredLazyBinding.await

  private val clientSystem: ActorSystem    = ActorSystem("config-cli")
  private val clientMat: ActorMaterializer = ActorMaterializer()(clientSystem)
  private val clientLocationService        = HttpLocationServiceFactory.makeLocalClient(clientSystem, clientMat)
  private val clientWiring                 = ClientCliWiring.noPrinting(clientSystem, clientLocationService)
  import clientWiring.commandLineRunner

  private val testFileUtils = new TestFileUtils(serverWiring.settings)

  val ArgsUtil = new ArgsUtil
  import ArgsUtil._

  val argsParser = new ArgsParser("csw-config-cli")

  override def beforeAll(): Unit  = testFileUtils.deleteServerFiles()
  override def beforeEach(): Unit = serverWiring.svnRepo.initSvnRepo()
  override def afterEach(): Unit = {
    testFileUtils.deleteServerFiles()
    if (Files.exists(Paths.get(outputFilePath))) Files.delete(Paths.get(outputFilePath))
  }
  override def afterAll(): Unit = {
    httpService.shutdown(UnknownReason).await
    clientWiring.actorRuntime.shutdown(UnknownReason).await
    serverWiring.actorRuntime.shutdown(UnknownReason).await
    Files.delete(Paths.get(inputFilePath))
    Files.delete(Paths.get(updatedInputFilePath))
    super.afterAll()
  }

  //DEOPSCSW-72: Retrieve a configuration file to a specified file location on a local disk
  test("should able to create a file in repo and read it from repo to local disk") {

    commandLineRunner.create(argsParser.parse(createMinimalArgs).get) shouldBe ConfigId(1)

    commandLineRunner.get(argsParser.parse(getLatestArgs).get) shouldBe Some(Paths.get(outputFilePath))

    // read locally saved output file (/tmp/output.conf) from disk and
    // match the contents with input file content
    readFile(outputFilePath) shouldEqual inputFileContents
  }

  test("should able to update, delete and check for existence of a file from repo") {

    commandLineRunner.create(argsParser.parse(createMinimalArgs).get)

    val getByDateArgs = Array("get", relativeRepoPath, "-o", outputFilePath, "--date", Instant.now.toString)

    val updateConfigId = commandLineRunner.update(argsParser.parse(updateAllArgs).get)

    commandLineRunner.get(argsParser.parse(getLatestArgs).get) shouldBe Some(Paths.get(outputFilePath))

    commandLineRunner.get(argsParser.parse(getByIdArgs :+ updateConfigId.id).get) shouldBe Some(
      Paths.get(outputFilePath)
    )

    //  read locally saved output file (/tmp/output.conf) from disk and
    //  match the contents with input file content
    readFile(outputFilePath) shouldEqual updatedInputFileContents

    commandLineRunner.get(argsParser.parse(getByDateArgs).get) shouldBe Some(Paths.get(outputFilePath))

    //  read locally saved output file (/tmp/output.conf) from disk and
    //  match the contents with input file content
    readFile(outputFilePath) shouldEqual inputFileContents

    //  file should exist
    val parsedExistsArgs1: Option[Options] = argsParser.parse(existsArgs)
    commandLineRunner.exists(parsedExistsArgs1.get) shouldBe true

    //  delete file
    val parsedDeleteArgs: Option[Options] = argsParser.parse(deleteArgs)
    commandLineRunner.delete(parsedDeleteArgs.get)

    //  deleted file should not exist
    val parsedExistsArgs: Option[Options] = argsParser.parse(existsArgs)
    commandLineRunner.exists(parsedExistsArgs.get) shouldBe false
  }

  test("should be able to list files and use filter pattern") {
    val normalFileName                                = "troubleshooting"
    val annexFileName                                 = "firmware"
    def relativeRepoPath(fileName: String, i: String) = s"path/hcd/$fileName$i.conf"

    //  create 4 normal files
    val normalFiles = for {
      i ← 1 to 4
    } yield relativeRepoPath(normalFileName, i.toString)
    normalFiles.map { fileName ⇒
      commandLineRunner.create(argsParser.parse(Array("create", fileName, "-i", inputFilePath, "-c", comment)).get)
    }

    //  create 3 annex files
    val annexFiles = for {
      i ← 1 to 3
    } yield relativeRepoPath(annexFileName, i.toString)
    annexFiles.map { fileName ⇒
      commandLineRunner
        .create(argsParser.parse(Array("create", fileName, "-i", inputFilePath, "--annex", "-c", comment)).get)
    }

    commandLineRunner.list(argsParser.parse(Array("list", "--annex", "--normal")).get) shouldBe empty

    commandLineRunner
      .list(argsParser.parse(Array("list", "--normal")).get)
      .map(_.path.toString)
      .toSet shouldBe normalFiles.toSet

    commandLineRunner
      .list(argsParser.parse(Array("list", "--annex")).get)
      .map(_.path.toString)
      .toSet shouldBe annexFiles.toSet

    commandLineRunner
      .list(argsParser.parse(Array("list", "--pattern", "/path/hcd/*.*")).get)
      .map(_.path.toString)
      .toSet shouldBe (annexFiles ++ normalFiles).toSet
  }

  test("should able to set, reset and get the active version of file.") {

    commandLineRunner.create(argsParser.parse(createMinimalArgs).get)

    val updateConfigId = commandLineRunner.update(argsParser.parse(updateAllArgs).get)

    //  set active version of file to id=1 and store it at location: /tmp/output.txt
    val parsedSetActiveArgs: Option[Options] = argsParser.parse(setActiveAllArgs)
    commandLineRunner.setActiveVersion(parsedSetActiveArgs.get)

    val getByDateArgs =
      Array("getActiveByTime", relativeRepoPath, "-o", outputFilePath, "--date", Instant.now.toString)

    //  get active version of file and store it at location: /tmp/output.txt
    val parsedGetActiveArgs: Option[Options] = argsParser.parse(getMinimalArgs)
    commandLineRunner.getActiveVersion(parsedGetActiveArgs.get).get shouldBe ConfigId(parsedSetActiveArgs.get.id.get)
    commandLineRunner.getActive(parsedGetActiveArgs.get) shouldBe Some(Paths.get(outputFilePath))

    //  read locally saved output file (/tmp/output.conf) from disk and
    //  match the contents with input file content
    readFile(outputFilePath) shouldEqual inputFileContents

    commandLineRunner.resetActiveVersion(argsParser.parse(resetActiveAllArgs).get)

    //  get active version of file and store it at location: /tmp/output.txt
    commandLineRunner.getActiveVersion(parsedGetActiveArgs.get).get shouldBe updateConfigId
    commandLineRunner.getActive(parsedGetActiveArgs.get) shouldBe Some(Paths.get(outputFilePath))

    //  read locally saved output file (/tmp/output.conf) from disk and
    //  match the contents with input file content
    readFile(outputFilePath) shouldEqual updatedInputFileContents

    commandLineRunner.getActiveByTime(argsParser.parse(getByDateArgs).get) shouldBe Some(Paths.get(outputFilePath))

    //  read locally saved output file (/tmp/output.conf) from disk and
    //  match the contents with input file content
    readFile(outputFilePath) shouldEqual inputFileContents
  }

  test("should able to fetch history of file.") {

    //  create file
    val parsedCreateArgs: Option[Options] = argsParser.parse(createMinimalArgs)
    val createConfigId                    = commandLineRunner.create(parsedCreateArgs.get)
    val createTS                          = Instant.now

    //  update file content
    val parsedUpdateArgs: Option[Options] = argsParser.parse(updateAllArgs)
    val updateConfigId                    = commandLineRunner.update(parsedUpdateArgs.get)

    //  update file content
    val parsedUpdate2Args: Option[Options] = argsParser.parse(updateAllArgs)
    val update2ConfigId                    = commandLineRunner.update(parsedUpdate2Args.get)
    val updateTS                           = Instant.now

    commandLineRunner.history(argsParser.parse(historyArgs).get).map(_.id) shouldBe List(update2ConfigId,
                                                                                         updateConfigId,
                                                                                         createConfigId)

    commandLineRunner
      .history(argsParser.parse(historyArgs :+ "--from" :+ createTS.toString :+ "--to" :+ updateTS.toString).get)
      .map(_.id) shouldBe List(update2ConfigId, updateConfigId)
  }

  test("should able to fetch history of active files.") {

    //  create file
    val parsedCreateArgs: Option[Options] = argsParser.parse(createMinimalArgs)
    val createConfigId                    = commandLineRunner.create(parsedCreateArgs.get)
    val createTS                          = Instant.now

    //  update file content
    val parsedUpdateArgs: Option[Options] = argsParser.parse(updateAllArgs)
    val updateConfigId                    = commandLineRunner.update(parsedUpdateArgs.get)

    //  update file content
    val parsedUpdate2Args: Option[Options] = argsParser.parse(updateAllArgs)
    val update2ConfigId                    = commandLineRunner.update(parsedUpdate2Args.get)

    val setActiveArgs = Array("setActiveVersion", relativeRepoPath, "--id", updateConfigId.id, "-c", comment)
    commandLineRunner.setActiveVersion(argsParser.parse(setActiveArgs).get)

    commandLineRunner.historyActive(argsParser.parse(historyActiveArgs).get).map(_.id) shouldBe List(updateConfigId,
                                                                                                     createConfigId)

    commandLineRunner.resetActiveVersion(argsParser.parse(resetActiveAllArgs).get)

    commandLineRunner
      .historyActive(
        argsParser.parse(historyActiveArgs :+ "--from" :+ createTS.toString :+ "--to" :+ Instant.now.toString).get
      )
      .map(_.id) shouldBe List(update2ConfigId, updateConfigId)
  }

  test("get repository MetaData from server") {
    val parsedMetaDataArgs: Option[Options] = argsParser.parse(meteDataArgs)
    val actualMetadata                      = commandLineRunner.getMetadata(parsedMetaDataArgs.get)
    actualMetadata.toString should not be empty
  }
}
