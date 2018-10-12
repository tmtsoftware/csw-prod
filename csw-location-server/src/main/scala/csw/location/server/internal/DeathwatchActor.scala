package csw.location.server.internal

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, Terminated}
import akka.cluster.ddata.Replicator.{Changed, Subscribe}
import csw.location.api.models.{AkkaLocation, Location}
import csw.location.api.scaladsl.LocationService
import csw.location.server.commons.{CswCluster, LocationServiceLogger}
import csw.location.server.internal.Registry.AllServices
import csw.logging.scaladsl.Logger

/**
 * DeathWatchActor tracks the health of all components registered with LocationService.
 *
 * @param locationService is used to unregister Actors that are no more alive
 */
private[location] class DeathwatchActor(locationService: LocationService) {
  import DeathwatchActor.Msg

  /**
   * Deathwatch behavior processes `DeathwatchActor.Msg` type events sent by replicator for newly registered Locations.
   * Terminated signal will be received upon termination of an actor that was being watched.
   *
   * @see [[akka.actor.Terminated]]
   */
  private[location] def behavior(watchedLocations: Set[Location]): Behavior[Msg] =
    Behaviors.receive[Msg] { (context, changeMsg) ⇒
      val log: Logger = LocationServiceLogger.getLogger(context)

      val allLocations = changeMsg.get(AllServices.Key).entries.values.toSet

      // Find out the ones that are not being watched and watch them
      val unwatchedLocations = allLocations diff watchedLocations

      // Ignore HttpLocation or TcpLocation (Do not watch)
      unwatchedLocations.foreach {
        case AkkaLocation(_, _, _, actorRef) ⇒
          log.debug(s"Started watching actor: ${actorRef.toString}")
          context.watch(actorRef)
        case _ ⇒ // ignore http and tcp location
      }
      //all locations are now watched
      behavior(allLocations)
    } receiveSignal {
      case (ctx, Terminated(deadActorRef)) ⇒
        val log: Logger = LocationServiceLogger.getLogger(ctx)

        log.warn(s"Un-watching terminated actor: ${deadActorRef.toString}")
        //stop watching the terminated actor
        ctx.unwatch(deadActorRef)
        //Unregister the dead location and remove it from the list of watched locations
        val maybeLocation = watchedLocations.find {
          case AkkaLocation(_, _, _, actorRef) ⇒ deadActorRef == actorRef
          case _                               ⇒ false
        }
        maybeLocation match {
          case Some(location) =>
            //if deadActorRef is mapped to a location, unregister it and remove it from watched locations
            locationService.unregister(location.connection)
            behavior(watchedLocations - location)
          case None ⇒
            //if deadActorRef does not match any location, don't change a thing!
            Behaviors.same
        }
    }
}

private[location] object DeathwatchActor {

  private val log: Logger = LocationServiceLogger.getLogger

  import akka.actor.typed.scaladsl.adapter._
  //message type handled by the for the typed deathwatch actor
  type Msg = Changed[AllServices.Value]

  /**
   * Start the DeathwatchActor using the given locationService
   *
   * @param cswCluster is used to get remote ActorSystem to create DeathwatchActor
   */
  def start(cswCluster: CswCluster, locationService: LocationService): ActorRef[Msg] = {
    log.debug("Starting Deathwatch actor")
    val actorRef = cswCluster.actorSystem.spawn(
      //span the actor with empty set of watched locations
      new DeathwatchActor(locationService).behavior(Set.empty),
      name = "location-service-death-watch-actor"
    )

    //Subscribed to replicator to get events for locations registered with LocationService
    cswCluster.replicator ! Subscribe(AllServices.Key, actorRef.toUntyped)
    actorRef
  }
}
