package csw.framework.internal.supervisor

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.ActorContext
import csw.command.client.messages.SupervisorMessage
import csw.command.client.models.framework.{LifecycleStateChanged, SupervisorLifecycleState}
import csw.logging.client.scaladsl.LoggerFactory
import csw.logging.api.scaladsl.Logger

private[framework] object LifecycleHandler {
  import SupervisorLifecycleState._

  sealed trait LifecycleHandlerMessage extends akka.actor.NoSerializationVerificationNeeded
  final case class UpdateState(update: SupervisorLifecycleState)                     extends LifecycleHandlerMessage
  final case class SubscribeState(subscriber:  ActorRef[LifecycleStateChanged])      extends LifecycleHandlerMessage
  final case class UnsubscribeState(subscriber:  ActorRef[LifecycleStateChanged])    extends LifecycleHandlerMessage
  final case class GetState(client: ActorRef[StateResponse])                         extends LifecycleHandlerMessage
  final case class SendState(client: ActorRef[SupervisorLifecycleState])             extends LifecycleHandlerMessage
  final case class StateResponse(supervisorLifecycleState: SupervisorLifecycleState) extends LifecycleHandlerMessage

  private type Subscriber  = ActorRef[LifecycleStateChanged]
  private type Subscribers = List[ActorRef[LifecycleStateChanged]]

  def apply(loggerFactory: LoggerFactory, svr: ActorRef[SupervisorMessage]): Behavior[LifecycleHandlerMessage] = {

    Behaviors.setup[LifecycleHandlerMessage] { ctx =>
      val log: Logger = loggerFactory.getLogger(ctx)

      def process(
               state: SupervisorLifecycleState,
               svr: ActorRef[SupervisorMessage],
               subscribers: Subscribers
             ): Behavior[LifecycleHandlerMessage] =
        Behaviors.receiveMessage {
          case UpdateState(newState) =>
            log.debug(s"Supervisor lifecycle state updated from [$state] to [$newState]")
            notify(newState, svr, subscribers)
            process(newState, svr, subscribers)
          case SubscribeState(subscriber) =>
            log.debug(s"Supervisor lifecycle state subscribed to by: $subscriber ")
            // When a client subscribes, send them the current lifecycle state
            notifyOne(state, svr, subscriber)
            process(state, svr, subscriber :: subscribers)
          case UnsubscribeState(subscriber) =>
            process(state, svr, subscribers.filter(_ != subscriber))
          case GetState(replyTo) =>
            replyTo ! StateResponse(state)
            Behaviors.same
          case SendState(replyTo) =>
            replyTo ! state
            Behaviors.same
        }

      def notifyOne(state: SupervisorLifecycleState, svr: ActorRef[SupervisorMessage], subscriber: Subscriber): Unit =
        subscriber ! LifecycleStateChanged(svr, state)

      def notify(state: SupervisorLifecycleState, svr: ActorRef[SupervisorMessage], subscribers: Subscribers): Unit = {
        log.debug(s"Supervisor sending state: $state to subscribers:[${subscribers.mkString(",")}]")
        subscribers.foreach(notifyOne(state, svr, _))
      }

      process(Idle, svr, List.empty[Subscriber])
    }
  }
}
