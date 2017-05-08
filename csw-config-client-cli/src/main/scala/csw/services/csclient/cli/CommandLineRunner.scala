package csw.services.csclient.cli

import java.nio.file.Path

import csw.services.config.api.models._
import csw.services.config.api.scaladsl.ConfigService
import csw.services.config.client.internal.ActorRuntime

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration

class CommandLineRunner(configService: ConfigService, actorRuntime: ActorRuntime) {

  import actorRuntime._

  //adminApi
  def create(options: Options): ConfigId = {
    val configData = ConfigData.fromPath(options.inputFilePath.get)
    val configId =
      await(configService.create(options.relativeRepoPath.get, configData, annex = options.annex, options.comment))
    println(s"File : ${options.relativeRepoPath.get} is created with id : ${configId.id}")
    configId
  }

  def update(options: Options): ConfigId = {
    val configData = ConfigData.fromPath(options.inputFilePath.get)
    val configId   = await(configService.update(options.relativeRepoPath.get, configData, options.comment))
    println(s"File : ${options.relativeRepoPath.get} is updated with id : ${configId.id}")
    configId
  }

  def get(options: Options): Option[Path] = {
    val configDataOpt = (options.date, options.id) match {
      case (Some(date), _) ⇒ await(configService.getByTime(options.relativeRepoPath.get, date))
      case (_, Some(id))   ⇒ await(configService.getById(options.relativeRepoPath.get, ConfigId(id)))
      case (_, _)          ⇒ await(configService.getLatest(options.relativeRepoPath.get))
    }

    configDataOpt match {
      case Some(configData) ⇒
        val outputFile = await(configData.toPath(options.outputFilePath.get))
        println(s"Output file is created at location : ${outputFile.toAbsolutePath}")
        Some(outputFile.toAbsolutePath)
      case None ⇒ None
    }
  }

  def delete(options: Options): Unit = {
    await(configService.delete(options.relativeRepoPath.get))
    println(s"File ${options.relativeRepoPath.get} deletion is completed.")
  }

  def list(options: Options): List[ConfigFileInfo] =
    (options.annex, options.normal) match {
      case (true, true) ⇒
        println("Please provide either normal or annex. See --help to know more.")
        List.empty[ConfigFileInfo]
      case (true, _) ⇒
        val fileEntries = await(configService.list(Some(FileType.Annex), options.pattern))
        fileEntries.foreach(i ⇒ println(s"${i.path}\t${i.id.id}\t${i.comment}"))
        fileEntries
      case (_, true) ⇒
        val fileEntries = await(configService.list(Some(FileType.Normal), options.pattern))
        fileEntries.foreach(i ⇒ println(s"${i.path}\t${i.id.id}\t${i.comment}"))
        fileEntries
      case (_, _) ⇒
        val fileEntries = await(configService.list(pattern = options.pattern))
        fileEntries.foreach(i ⇒ println(s"${i.path}\t${i.id.id}\t${i.comment}"))
        fileEntries
    }

  def history(options: Options): Unit = {
    val histList = await(configService.history(options.relativeRepoPath.get, options.maxFileVersions))
    histList.foreach(h => println(s"${h.id.id}\t${h.time}\t${h.comment}"))
  }

  def setActiveVersion(options: Options): Unit = {
    val maybeConfigId = options.id.map(id ⇒ ConfigId(id))
    await(configService.setActiveVersion(options.relativeRepoPath.get, maybeConfigId.get, options.comment))
    println(s"${options.relativeRepoPath.get} file with id:${maybeConfigId.get.id} is set as default")
  }

  def resetActiveVersion(options: Options): Unit = {
    await(configService.resetActiveVersion(options.relativeRepoPath.get, options.comment))
    println(s"${options.relativeRepoPath.get} file is reset to default")
  }

  def getActiveVersion(options: Options): ConfigId = {
    val configId = await(configService.getActiveVersion(options.relativeRepoPath.get))
    println(s"${configId.id} is the active version of the file.")
    configId
  }

  def getActiveByTime(options: Options): Unit = {
    val configDataOpt = await(configService.getActiveByTime(options.relativeRepoPath.get, options.date.get))

    configDataOpt match {
      case Some(configData) ⇒
        val outputFile = await(configData.toPath(options.outputFilePath.get))
        println(s"Output file is created at location : ${outputFile.toAbsolutePath}")
      case None ⇒
    }

  }

  def getMetadata(options: Options): ConfigMetadata = {
    val metaData = await(configService.getMetadata)
    println(metaData)
    metaData
  }

  //clientApi
  def exists(options: Options): Boolean = {
    val exists = await(configService.exists(options.relativeRepoPath.get))
    println(s"File ${options.relativeRepoPath.get} exists in the repo? : $exists")
    exists
  }

  def getActive(options: Options): Option[Path] = {
    val configDataOpt = await(configService.getActive(options.relativeRepoPath.get))

    configDataOpt match {
      case Some(configData) ⇒
        val outputFile = await(configData.toPath(options.outputFilePath.get))
        println(s"Output file is created at location : ${outputFile.toAbsolutePath}")
        Some(outputFile.toAbsolutePath)
      case None ⇒ None
    }
  }

  //command line app is by nature blocking.
  //Do not use such method in library/server side code
  def await[T](future: Future[T]): T = Await.result(future, Duration.Inf)
}
