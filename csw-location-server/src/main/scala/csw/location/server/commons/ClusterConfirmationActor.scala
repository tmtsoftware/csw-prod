package csw.location.server.commons

import akka.Done
import akka.actor.{Actor, Props}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import csw.location.server.commons.ClusterConfirmationActor.HasJoinedCluster

private[location] class ClusterConfirmationActor extends Actor {

  private val cluster = Cluster(context.system)

  override def preStart(): Unit = cluster.subscribe(self, InitialStateAsEvents, classOf[MemberEvent])
  override def postStop(): Unit = cluster.unsubscribe(self)

  var done: Option[Done] = None

  override def receive: Receive = {
    case MemberUp(member) if member.address == cluster.selfAddress       ⇒ done = Some(Done)
    case MemberWeaklyUp(member) if member.address == cluster.selfAddress ⇒ done = Some(Done)
    case HasJoinedCluster                                                ⇒ sender() ! done
  }

}

private[location] object ClusterConfirmationActor {
  def props(): Props = Props(new ClusterConfirmationActor)

  case object HasJoinedCluster
}
