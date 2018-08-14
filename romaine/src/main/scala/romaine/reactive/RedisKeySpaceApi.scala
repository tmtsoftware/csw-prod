package romaine.reactive

import akka.stream.scaladsl.Source
import reactor.core.publisher.FluxSink.OverflowStrategy
import romaine._
import romaine.extensions.SourceExtensions.RichSource

import scala.concurrent.{ExecutionContext, Future}

class RedisKeySpaceApi[K, V](
    redisPSubscribeApi: RedisPSubscribeScalaApi[String, String],
    redisAsyncScalaApi: RedisAsyncScalaApi[K, V]
)(implicit redisKeySpaceCodec: RedisKeySpaceCodec[K, V], ec: ExecutionContext) {

  private val redisSubscriptionApi: RedisSubscriptionApi[String, String] = new RedisSubscriptionApi(() => redisPSubscribeApi)

  private val SET_OPERATION     = "set"
  private val EXPIRED_OPERATION = "expired"
  private val KEYSPACE_PATTERN  = "__keyspace@0__:"

  def watchKeyspaceValue(
      keys: List[String],
      overflowStrategy: OverflowStrategy
  ): Source[RedisResult[K, Option[V]], RedisSubscription[String]] = {

    redisSubscriptionApi
      .subscribe(keys.map(KEYSPACE_PATTERN + _), overflowStrategy)
      .filter(pm => pm.value == SET_OPERATION || pm.value == EXPIRED_OPERATION)
      .mapAsync(1) { pm =>
        val key = redisKeySpaceCodec.fromKeyString(pm.key)
        pm.value match {
          case SET_OPERATION     => redisAsyncScalaApi.get(key).map(valueOpt ⇒ (key, valueOpt))
          case EXPIRED_OPERATION => Future((key, None))
        }
      }
      .collect {
        case (k, v) ⇒ RedisResult(k, v)
      }
      .distinctUntilChanged
  }

  def watchKeyspaceValueAggregation(
      keys: List[String],
      overflowStrategy: OverflowStrategy,
      reducer: Iterable[Option[V]] => V
  ): Source[V, RedisSubscription[String]] = {
    watchKeyspaceValue(keys, overflowStrategy)
      .scan(Map.empty[K, Option[V]]) {
        case (data, RedisResult(key, value)) ⇒ data + (key → value)
      }
      .map(data => reducer(data.values))
      .distinctUntilChanged
  }

  def watchKeyspaceField[TField](
      keys: List[String],
      overflowStrategy: OverflowStrategy,
      fieldMapper: V => TField
  ): Source[RedisResult[K, Option[TField]], RedisSubscription[String]] = {
    val stream: Source[RedisResult[K, Option[TField]], RedisSubscription[String]] =
      watchKeyspaceValue(keys, overflowStrategy).map {
        case RedisResult(k, Some(v)) => RedisResult(k, Some(fieldMapper(v)))
        case RedisResult(k, _)       => RedisResult(k, None)
      }
    stream.distinctUntilChanged
  }

  def watchKeyspaceFieldAggregation[TField](
      keys: List[String],
      overflowStrategy: OverflowStrategy,
      fieldMapper: V => TField,
      reducer: Iterable[Option[TField]] => TField
  ): Source[TField, RedisSubscription[String]] = {
    watchKeyspaceField(keys, overflowStrategy, fieldMapper)
      .scan(Map.empty[K, Option[TField]]) {
        case (data, RedisResult(key, value)) ⇒ data + (key → value)
      }
      .map(data => reducer(data.values))
      .distinctUntilChanged
  }
}
//distinct events - DONE
//subscriptionResult with unsubscribe and quit - DONE
//watch termination - DONE
//remove aggregate key - DONE
//todo: support for delete and expired, etc
//todo: RedisWatchSubscription try to remove type parameter