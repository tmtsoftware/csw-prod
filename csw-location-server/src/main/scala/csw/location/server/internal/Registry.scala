package csw.location.server.internal

import akka.cluster.Cluster
import akka.cluster.ddata.Replicator._
import akka.cluster.ddata._
import csw.location.api.commons.Constants
import csw.location.api.models.{Connection, Location}

import scala.concurrent.duration.DurationDouble

/**
 * Registry is used to create distributed data and manage its update and get messages for replicator
 *
 * @param Key the distributed data key
 * @param EmptyValue the default value of distributed data key
 * @tparam K the type of distributed data key
 * @tparam V the type of ReplicatedData
 */
private[location] class Registry[K <: Key[V], V <: ReplicatedData](val Key: K, val EmptyValue: V) {

  type Value = V

  /**
   * Creates an update message for replicator and it ensures that the response goes out only after majority nodes are written to
   *
   * @see [[akka.cluster.ddata.Replicator.Update]]
   * @param f a callback function which is passed to Replicator.Update
   */
  private[location] def update(f: V ⇒ V, initialValue: V = EmptyValue): Update[V] =
    Update(Key, initialValue, WriteMajority(5.seconds))(f)

  /**
   * Creates a get message for replicator. Unlike for update, it will read from the local cache of the node
   *
   * @see [[akka.cluster.ddata.Replicator.Get]]
   */
  private[location] def get: Get[V] = Get(Key, ReadLocal)

  /**
   * Creates a get message for replicator and it ensures that data is read and merged from a majority of replicas,
   * i.e. at least N/2 + 1 replicas, where N is the number of nodes in the cluster
   *
   * @see [[akka.cluster.ddata.Replicator.Get]]
   */
  private[location] def getByMajority: Get[V] = Get(Key, ReadMajority(5.seconds))
}

private[location] object Registry {

  /**
   * AllServices is a distributed map from connection to location.
   * It is used to get list of all registered locations at any point in time
   */
  object AllServices
      extends Registry[LWWMapKey[Connection, Location], LWWMap[Connection, Location]](
        Key = LWWMapKey(Constants.RegistryKey),
        EmptyValue = LWWMap.empty
      )

  /**
   * Service is a distributed registry which holds a location value against a connection-name.
   * At times, a location may not be available for a given connection-name, hence the location is an optional value.
   * It is used to track a single connection by subscribing events generated when associated location changes.
   *
   * @note service has key as LWWRegisterKey[Option[Location]] as it represents the type of value that LWWRegister will hold
   *       but the value of LWWRegisterKey will still be connection-name
   */
  class Service(connection: Connection)(implicit cluster: Cluster)
      extends Registry[LWWRegisterKey[Option[Location]], LWWRegister[Option[Location]]](
        Key = LWWRegisterKey(connection.name),
        EmptyValue = LWWRegister(Option.empty)
      )
}
