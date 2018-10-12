package csw.location

/**
 * == Location Service ==
 *
 * The Location Service implemented in this project is based on CRDT (Conflict Free Replicated Data).
 * The Location Service helps you resolve the hostname and port number for a service which can be used for further communication.
 * In case of Akka connection, It helps you to resolve component reference with which you can send messages
 *
 * Location Service supports three types of services as follows: Akka, Tcp and HTTP based services.
 *
 * Before calling LocationServiceFactory.make() to get an handle of LocationService,
 * it is recommended to have below environment variables set to System global environments or system properties:
 *  - interfaceName (The network interface where akka cluster is formed.) Ex. interfaceName=eth0 if not set, first inet4 interface by priority will be picked
 *  - clusterSeeds (The host address and port of the seedNodes of the cluster) Ex. clusterSeeds="192.168.121.10:3552, 192.168.121.11:3552"
 *  - clusterPort (Specify port on which to start this service) Ex. clusterPort=3552 if this property is not set, service will start on random port.
 *
 * Location service make use of classes defined in `csw-messages` package, for example:
 * - [[csw.location.api.models.Location]] :
 *  When you resolve component location using [[csw.location.api.scaladsl.LocationService]], you get back this location.
 *  There are three types of Locations:
 *   - [[csw.location.api.models.AkkaLocation]]: holds hostname, port and actorRef which is ready to receive [[csw.params.commands.Command]] from outside.
 *   - [[csw.location.api.models.TcpLocation]]: holds hostname and port which can be used for further communication.
 *   - [[csw.location.api.models.HttpLocation]]: holds hostname and port which can be used for further communication.
 *
 * - [[csw.location.api.models.Connection]]  :
 *  Connection holds the identity of component which includes `component name` and [[csw.location.api.models.ComponentType]].
 *  Every component needs to register with location service using unique connection.
 *  There are three types of Connections:
 *  - [[csw.location.api.models.Connection.AkkaConnection]]
 *  - [[csw.location.api.models.Connection.TcpConnection]]
 *  - [[csw.location.api.models.Connection.HttpConnection]]
 *
 * Another important actor from LocationService is `DeathwatchActor` [[csw.location.server.internal.DeathwatchActor]]
 * which gets created with LocationService initialization. Job of this actor is to watch health of every component and on termination,
 * unregister terminated component from LocationService.
 *
 * Complete guide of usage of different API's provided by LocationService is available at:
 * https://tmtsoftware.github.io/csw/services/location.html
 */
package object server {}
