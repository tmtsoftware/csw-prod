package csw.config

import java.io.InputStream
import java.nio.file.{Path, Paths}
import java.time.Instant

import akka.actor.ActorSystem
import akka.actor.CoordinatedShutdown.UnknownReason
import akka.stream.{ActorMaterializer, Materializer}
import csw.config.api.models.{ConfigData, ConfigId, ConfigMetadata, FileType}
import csw.config.api.scaladsl.{ConfigClientService, ConfigService}
import csw.config.client.scaladsl.ConfigClientFactory
import csw.config.server.ServerWiring
import csw.config.server.commons.TestFileUtils
import csw.location.api.scaladsl.LocationService
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.location.server.http.HTTPLocationService
import org.scalatest._

import scala.async.Async._
import scala.concurrent.duration.DurationLong
import scala.concurrent.{Await, Future}

class ConfigClientExampleTest extends HTTPLocationService {

  implicit private val system: ActorSystem     = ActorSystem("config-server")
  implicit val mat: Materializer               = ActorMaterializer()
  private val locationService: LocationService = HttpLocationServiceFactory.makeLocalClient
  private val serverWiring: ServerWiring       = ServerWiring.make(system, locationService)
  private val httpService                      = serverWiring.httpService

  private val testFileUtils = new TestFileUtils(serverWiring.settings)

  import serverWiring.actorRuntime._

  //#create-api
  //config client API
  val clientApi: ConfigClientService = ConfigClientFactory.clientApi(actorSystem, locationService)
  //config admin API
  val adminApi: ConfigService = ConfigClientFactory.adminApi(actorSystem, locationService)
  //#create-api

  override def beforeAll(): Unit = {
    testFileUtils.deleteServerFiles()
    Await.result(httpService.registeredLazyBinding, 5.seconds)
  }
  override def beforeEach(): Unit = serverWiring.svnRepo.initSvnRepo()
  override def afterEach(): Unit  = testFileUtils.deleteServerFiles()

  override def afterAll(): Unit = {
    Await.result(httpService.shutdown(UnknownReason), 10.seconds)
    super.afterAll()
  }

  //#declare_string_config
  val defaultStrConf: String = "foo { bar { baz : 1234 } }"
  //#declare_string_config

  test("exists") {
    //#exists
    //construct the path
    val filePath = Paths.get("/tmt/trmobone/assembly/hcd.conf")

    val doneF = async {
      // create file using admin API
      await(adminApi.create(filePath, ConfigData.fromString(defaultStrConf), annex = false, "First commit"))

      //check if file exists with config service
      val exists: Boolean = await(clientApi.exists(filePath))
      exists shouldBe true
    }
    Await.result(doneF, 5.seconds)
    //#exists
  }

  test("getActive") {
    //#getActive
    val doneF = async {
      // construct the path
      val filePath = Paths.get("/tmt/trmobone/assembly/hcd.conf")

      await(adminApi.create(filePath, ConfigData.fromString(defaultStrConf), annex = false, "First commit"))

      val activeFile: Option[ConfigData] = await(clientApi.getActive(filePath))
      await(activeFile.get.toStringF(mat)) shouldBe defaultStrConf
    }
    Await.result(doneF, 5.seconds)
    //#getActive
  }

  test("create-update-delete") {
    val futC =
      //#create
      async {
        //construct ConfigData from String containing ASCII text
        val configString: String =
          """
        // Name: ComponentType ConnectionType
        {
          name: lgsTromboneHCD
          type: Hcd
          connectionType: [akka]
        }
        """.stripMargin
        val config1: ConfigData = ConfigData.fromString(configString)

        //construct ConfigData from a local file containing binary data
        val srcFilePath         = getClass.getClassLoader.getResource("smallBinary.bin").toURI
        val config2: ConfigData = ConfigData.fromPath(Paths.get(srcFilePath))

        //construct ConfigData from Array[Byte] by reading a local file
        val stream: InputStream    = getClass.getClassLoader.getResourceAsStream("smallBinary.bin")
        def byteArray: Array[Byte] = Stream.continually(stream.read).takeWhile(_ != -1).map(_.toByte).toArray
        val config3                = ConfigData.fromBytes(byteArray)

        //store the config, at a specified path as normal text file
        val id1: ConfigId =
          await(adminApi.create(Paths.get("/hcd/trombone/overnight.conf"), config1, false, "review done"))

        //store the config, at a specified path as a binary file in annex store
        val id2: ConfigId =
          await(adminApi.create(Paths.get("/hcd/trombone/firmware.bin"), config2, true, "smoke test done"))

        //store the config, at a specified path as a binary file in annex store
        val id3: ConfigId =
          await(adminApi.create(Paths.get("/hcd/trombone/debug.bin"), config3, true, "new file from vendor"))

        //CAUTION: for demo example setup these IDs are returned. Don't assume them in production setup.
        id1 shouldEqual ConfigId(1)
        id2 shouldEqual ConfigId(3)
        id3 shouldEqual ConfigId(5)
      }
    Await.result(futC, 2.seconds)
    //#create

    //#update
    val futU = async {
      val destPath = Paths.get("/hcd/trombone/debug.bin")
      val newId = await(
        adminApi
          .update(destPath, ConfigData.fromString(defaultStrConf), comment = "debug statements")
      )

      //validate the returned id
      newId shouldEqual ConfigId(7)
    }
    Await.result(futU, 2.seconds)
    //#update

    //#delete
    val futD = async {
      val unwantedFilePath = Paths.get("/hcd/trombone/debug.bin")
      await(adminApi.delete(unwantedFilePath, "no longer needed"))
      //validates the file is deleted
      await(adminApi.getLatest(unwantedFilePath)) shouldBe None
    }
    Await.result(futD, 2.seconds)
    //#delete
  }

  test("getById") {
    //#getById
    val doneF = async {
      // create a file using API first
      val filePath = Paths.get("/tmt/trmobone/assembly/hcd.conf")
      val id: ConfigId =
        await(adminApi.create(filePath, ConfigData.fromString(defaultStrConf), annex = false, "First commit"))

      //validate
      val actualData = await(adminApi.getById(filePath, id)).get
      await(actualData.toStringF(mat)) shouldBe defaultStrConf
    }
    Await.result(doneF, 2.seconds)
    //#getById
  }

  test("getLatest") {
    //#getLatest
    val assertionF: Future[Assertion] = async {
      //create a file
      val filePath = Paths.get("/test.conf")
      await(adminApi.create(filePath, ConfigData.fromString(defaultStrConf), annex = false, "initial configuration"))

      //override the contents
      val newContent = "I changed the contents!!!"
      await(adminApi.update(filePath, ConfigData.fromString(newContent), "changed!!"))

      //get the latest file
      val newConfigData = await(adminApi.getLatest(filePath)).get
      //validate
      await(newConfigData.toStringF(mat)) shouldBe newContent
    }
    Await.result(assertionF, 2.seconds)
    //#getLatest
  }

  test("getByTime") {
    //#getByTime
    val assertionF = async {
      val tInitial = Instant.MIN
      //create a file
      val filePath = Paths.get("/a/b/c/test.conf")
      await(adminApi.create(filePath, ConfigData.fromString(defaultStrConf), annex = false, "initial configuration"))

      //override the contents
      val newContent = "I changed the contents!!!"
      await(adminApi.update(filePath, ConfigData.fromString(newContent), "changed!!"))

      val initialData: ConfigData = await(adminApi.getByTime(filePath, tInitial)).get
      await(initialData.toStringF(mat)) shouldBe defaultStrConf

      val latestData = await(adminApi.getByTime(filePath, Instant.now())).get
      await(latestData.toStringF(mat)) shouldBe newContent
    }
    Await.result(assertionF, 2.seconds)
    //#getByTime
  }

  test("list") {
    //#list
    //Here's a list of tuples containing FilePath and FileTyepe(Annex / Normal)
    val paths: List[(Path, FileType)] = List[(Path, FileType)](
      (Paths.get("a/c/trombone.conf"), FileType.Annex),
      (Paths.get("a/b/c/hcd/hcd.conf"), FileType.Normal),
      (Paths.get("a/b/assembly/assembly1.fits"), FileType.Annex),
      (Paths.get("a/b/c/assembly/assembly2.fits"), FileType.Normal),
      (Paths.get("testing/test.conf"), FileType.Normal)
    )

    //create config files at those paths
    paths map {
      case (path, fileType) ⇒
        val createF = async {
          await(
            adminApi.create(path, ConfigData.fromString(defaultStrConf), FileType.Annex == fileType, "initial commit")
          )
        }
        Await.result(createF, 2.seconds)
    }

    val assertionF = async {
      //retrieve list of all files; for demonstration purpose show validate return values
      await(adminApi.list()).map(info ⇒ info.path).toSet shouldBe paths.map {
        case (path, fileType) ⇒ path
      }.toSet

      //retrieve list of files based on type; for demonstration purpose validate return values
      await(adminApi.list(Some(FileType.Annex))).map(info ⇒ info.path).toSet shouldBe paths.collect {
        case (path, fileType) if (fileType == FileType.Annex) ⇒ path
      }.toSet
      await(adminApi.list(Some(FileType.Normal))).map(info ⇒ info.path).toSet shouldBe paths.collect {
        case (path, fileType) if (fileType == FileType.Normal) ⇒ path
      }.toSet

      //retrieve list using pattern; for demonstration purpose validate return values
      await(adminApi.list(None, Some(".*.conf"))).map(info ⇒ info.path.toString).toSet shouldBe Set(
        "a/b/c/hcd/hcd.conf",
        "a/c/trombone.conf",
        "testing/test.conf"
      )
      //retrieve list using pattern and file type; for demonstration purpose validate return values
      await(adminApi.list(Some(FileType.Normal), Some(".*.conf"))).map(info ⇒ info.path.toString).toSet shouldBe
      Set("a/b/c/hcd/hcd.conf", "testing/test.conf")
      await(adminApi.list(Some(FileType.Annex), Some("a/c.*"))).map(info ⇒ info.path.toString).toSet shouldBe
      Set("a/c/trombone.conf")
      await(adminApi.list(Some(FileType.Normal), Some("test.*"))).map(info ⇒ info.path.toString).toSet shouldBe
      Set("testing/test.conf")
    }
    Await.result(assertionF, 2.seconds)
    //#list
  }

  test("history") {
    //#history
    val assertionF = async {
      val filePath = Paths.get("/a/test.conf")
      val id0      = await(adminApi.create(filePath, ConfigData.fromString(defaultStrConf), annex = false, "first commit"))

      //override the contents twice
      val tBeginUpdate = Instant.now()
      val id1          = await(adminApi.update(filePath, ConfigData.fromString("changing contents"), "second commit"))
      val id2          = await(adminApi.update(filePath, ConfigData.fromString("changing contents again"), "third commit"))
      val tEndUpdate   = Instant.now()

      //full file history
      val fullHistory = await(adminApi.history(filePath))
      fullHistory.map(_.id) shouldBe List(id2, id1, id0)
      fullHistory.map(_.comment) shouldBe List("third commit", "second commit", "first commit")

      //drop initial revision and take only update revisions
      await(adminApi.history(filePath, tBeginUpdate, tEndUpdate)).map(_.id) shouldBe List(id2, id1)

      //take last two revisions
      await(adminApi.history(filePath, maxResults = 2)).map(_.id) shouldBe List(id2, id1)
    }
    Await.result(assertionF, 3.seconds)
    //#history
  }

  test("historyActive-setActiveVersion-resetActiveVersion-getActiveVersion-getActiveByTime") {
    //#active-file-mgmt
    val assertionF = async {
      val tBegin   = Instant.now()
      val filePath = Paths.get("/a/test.conf")
      //create will make the 1st revision active with a default comment
      val id1 = await(adminApi.create(filePath, ConfigData.fromString(defaultStrConf), annex = false, "first"))
      await(adminApi.historyActive(filePath)).map(_.id) shouldBe List(id1)
      //ensure active version is set
      await(adminApi.getActiveVersion(filePath)).get shouldBe id1

      //override the contents four times
      await(adminApi.update(filePath, ConfigData.fromString("changing contents"), "second"))
      val id3 = await(adminApi.update(filePath, ConfigData.fromString("changing contents again"), "third"))
      val id4 = await(adminApi.update(filePath, ConfigData.fromString("final contents"), "fourth"))
      val id5 = await(adminApi.update(filePath, ConfigData.fromString("final final contents"), "fifth"))

      //update doesn't change the active revision
      await(adminApi.historyActive(filePath)).map(_.id) shouldBe List(id1)

      //play with active version
      await(adminApi.setActiveVersion(filePath, id3, s"$id3 active"))
      await(adminApi.setActiveVersion(filePath, id4, s"$id4 active"))
      await(adminApi.getActiveVersion(filePath)).get shouldBe id4
      val tEnd = Instant.now()
      //reset active version to latest
      await(adminApi.resetActiveVersion(filePath, "latest active"))
      await(adminApi.getActiveVersion(filePath)).get shouldBe id5
      //finally set initial version as active
      await(adminApi.setActiveVersion(filePath, id1, s"$id1 active"))
      await(adminApi.getActiveVersion(filePath)).get shouldBe id1

      //validate full history
      val fullHistory = await(adminApi.historyActive(filePath))
      fullHistory.map(_.id) shouldBe List(id1, id5, id4, id3, id1)
      fullHistory.map(_.comment) shouldBe List(s"$id1 active",
                                               "latest active",
                                               s"$id4 active",
                                               s"$id3 active",
                                               "initializing active file with the first version")

      //drop initial revision and take only update revisions
      val fragmentedHistory = await(adminApi.historyActive(filePath, tBegin, tEnd))
      fragmentedHistory.size shouldBe 3

      //take last three revisions
      await(adminApi.historyActive(filePath, maxResults = 3)).map(_.id) shouldBe List(id1, id5, id4)

      //get contents of active version at a specified instance
      val initialContents = await(adminApi.getActiveByTime(filePath, tBegin)).get
      await(initialContents.toStringF(mat)) shouldBe defaultStrConf
    }
    Await.result(assertionF, 5.seconds)
    //#active-file-mgmt
  }

  test("getMetadata") {
    //#getMetadata
    val assertF = async {
      val metaData: ConfigMetadata = await(adminApi.getMetadata)
      //repository path must not be empty
      metaData.repoPath should not be empty
    }
    Await.result(assertF, 2.seconds)
    //#getMetadata
  }
}
