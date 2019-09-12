package csw.event.client.internal.redis

import akka.actor.typed.ActorRef
import akka.stream.Materializer
import akka.stream.scaladsl.{Keep, Source}
import akka.{Done, NotUsed}
import csw.params.events._
import csw.params.core.models.Subsystem
import csw.event.api.exceptions.EventServerNotAvailable
import csw.event.api.scaladsl.{EventSubscriber, EventSubscription, SubscriptionMode}
import csw.event.client.internal.commons.{EventServiceLogger, EventSubscriberUtil}
import io.lettuce.core.{RedisClient, RedisURI}
import reactor.core.publisher.FluxSink.OverflowStrategy
import romaine.RomaineFactory
import romaine.async.RedisAsyncApi
import romaine.codec.RomaineCodec
import romaine.exceptions.RedisServerNotAvailable
import romaine.reactive.{RedisSubscription, RedisSubscriptionApi}

import scala.async.Async._
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

/**
 * An implementation of [[csw.event.api.scaladsl.EventSubscriber]] API which uses Redis as the provider for publishing
 * and subscribing events.
 *
 * @param redisURI    future containing connection details for the Redis/Sentinel connections.
 * @param redisClient redis client available from lettuce
 * @param ec          the execution context to be used for performing asynchronous operations
 * @param mat         the materializer to be used for materializing underlying streams
 */
private[event] class RedisSubscriber(redisURI: Future[RedisURI], redisClient: RedisClient)(
    implicit ec: ExecutionContext,
    mat: Materializer
) extends EventSubscriber {

  import EventRomaineCodecs._

  private val log                 = EventServiceLogger.getLogger
  private val eventSubscriberUtil = new EventSubscriberUtil()

  private val romaineFactory = new RomaineFactory(redisClient)

  private val asyncApi: RedisAsyncApi[EventKey, Event] = romaineFactory.redisAsyncApi[EventKey, Event](redisURI)

  private def subscriptionApi[T: RomaineCodec](): RedisSubscriptionApi[T, Event] =
    romaineFactory.redisSubscriptionApi[T, Event](redisURI)

  override def subscribe(eventKeys: Set[EventKey]): Source[Event, EventSubscription] = {
    log.info(s"Subscribing to event keys: $eventKeys")
    val eventSubscriptionApi: RedisSubscriptionApi[EventKey, Event] = subscriptionApi()

    val latestEventStream: Source[Event, NotUsed] = Source.fromFuture(get(eventKeys)).mapConcat(identity)
    val redisStream: Source[Event, RedisSubscription] =
      eventSubscriptionApi.subscribe(eventKeys.toList, OverflowStrategy.LATEST).map(_.value)

    latestEventStream.concatMat(eventStream(eventKeys, redisStream))(Keep.right)
  }

  override def subscribe(
      eventKeys: Set[EventKey],
      every: FiniteDuration,
      mode: SubscriptionMode
  ): Source[Event, EventSubscription] = subscribe(eventKeys).via(eventSubscriberUtil.subscriptionModeStage(every, mode))

  override def subscribeAsync(eventKeys: Set[EventKey], callback: Event => Future[_]): EventSubscription =
    eventSubscriberUtil.subscribeAsync(subscribe(eventKeys), callback)

  override def subscribeAsync(
      eventKeys: Set[EventKey],
      callback: Event => Future[_],
      every: FiniteDuration,
      mode: SubscriptionMode
  ): EventSubscription = eventSubscriberUtil.subscribeAsync(subscribe(eventKeys, every, mode), callback)

  override def subscribeCallback(eventKeys: Set[EventKey], callback: Event => Unit): EventSubscription =
    eventSubscriberUtil.subscribeCallback(subscribe(eventKeys), callback)

  override def subscribeCallback(
      eventKeys: Set[EventKey],
      callback: Event => Unit,
      every: FiniteDuration,
      mode: SubscriptionMode
  ): EventSubscription = eventSubscriberUtil.subscribeCallback(subscribe(eventKeys, every, mode), callback)

  override def subscribeActorRef(eventKeys: Set[EventKey], actorRef: ActorRef[Event]): EventSubscription =
    subscribeCallback(eventKeys, eventSubscriberUtil.actorCallback(actorRef))

  override def subscribeActorRef(
      eventKeys: Set[EventKey],
      actorRef: ActorRef[Event],
      every: FiniteDuration,
      mode: SubscriptionMode
  ): EventSubscription = subscribeCallback(eventKeys, eventSubscriberUtil.actorCallback(actorRef), every, mode)

  override def pSubscribe(subsystem: Subsystem, pattern: String): Source[Event, EventSubscription] = {
    val keyPattern = s"${subsystem.entryName}.$pattern"
    log.info(s"Subscribing to event key pattern: $keyPattern")

    val patternSubscriptionApi: RedisSubscriptionApi[String, Event] = subscriptionApi()
    val redisStream: Source[Event, RedisSubscription] =
      patternSubscriptionApi.psubscribe(List(keyPattern), OverflowStrategy.LATEST).map(_.value)
    eventStream(keyPattern, redisStream)
  }

  override def pSubscribeCallback(subsystem: Subsystem, pattern: String, callback: Event => Unit): EventSubscription =
    eventSubscriberUtil.pSubscribe(pSubscribe(subsystem, pattern), callback)

  override def get(eventKeys: Set[EventKey]): Future[Set[Event]] = Future.sequence(eventKeys.map(get))

  override def get(eventKey: EventKey): Future[Event] = async {
    log.info(s"Fetching event key: $eventKey")
    val event = await(recoverWithError(asyncApi.get(eventKey)))
    event.getOrElse(Event.invalidEvent(eventKey))
  }

  private def eventStream[T](
      eventKeys: T,
      eventStreamF: Source[Event, RedisSubscription]
  ): Source[Event, EventSubscription] =
    eventStreamF.mapMaterializedValue { redisSubscription =>
      new EventSubscription {
        override def unsubscribe(): Future[Done] = {
          log.info(s"Unsubscribing for keys=$eventKeys")
          redisSubscription.unsubscribe()
        }
        override def ready(): Future[Done] = recoverWithError(redisSubscription.ready())
      }
    }

  private def recoverWithError[T](f: Future[T]) = f.recover {
    case RedisServerNotAvailable(ex) => throw EventServerNotAvailable(ex)
  }

}
