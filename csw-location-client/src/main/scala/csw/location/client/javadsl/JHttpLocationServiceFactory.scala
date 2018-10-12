package csw.location.client.javadsl

import akka.actor.ActorSystem
import akka.stream.Materializer
import csw.location.api.javadsl.ILocationService
import csw.location.client.extensions.LocationServiceExt.RichLocationService
import csw.location.client.scaladsl.HttpLocationServiceFactory

/**
 * The factory is used to create ILocationService instance.
 */
object JHttpLocationServiceFactory {

  /**
   * Use this factory method to create http location client when cluster seed is running locally.
   * Cluster seed starts location http server on port 7654.
   * Short running command line applications can use this factory method to get http access to location service,
   * so that they do not need to join and leave akka cluster.
   * */
  def makeLocalClient(actorSystem: ActorSystem, mat: Materializer): ILocationService =
    HttpLocationServiceFactory.makeLocalClient(actorSystem, mat).asJava(actorSystem.dispatcher)

  /**
   * Use this factory method to create http location client when cluster seed is running remotely.
   * Cluster seed starts location http server on port 7654.
   * This client tries to connect to the location server running on first cluster seed node.
   * Hence clusterSeeds property should be set in the environment variables else [[csw.location.api.exceptions.ClusterSeedsNotFound]] exception will be thrown.
   * */
  def makeRemoteClient(actorSystem: ActorSystem, mat: Materializer): ILocationService =
    HttpLocationServiceFactory.makeRemoteClient(actorSystem, mat).asJava(actorSystem.dispatcher)
}
