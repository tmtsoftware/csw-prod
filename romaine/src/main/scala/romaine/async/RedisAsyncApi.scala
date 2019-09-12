package romaine.async

import akka.Done
import io.lettuce.core.api.async.RedisAsyncCommands
import romaine.RedisResult
import romaine.exceptions.RedisServerNotAvailable
import romaine.extensions.FutureExtensions.RichFuture

import scala.jdk.CollectionConverters._
import scala.compat.java8.FutureConverters.CompletionStageOps
import scala.compat.java8.OptionConverters.RichOptionalGeneric
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

class RedisAsyncApi[K, V](redisAsyncCommands: Future[RedisAsyncCommands[K, V]])(implicit ec: ExecutionContext) {

  def set(key: K, value: V): Future[Done] =
    redisAsyncCommands.flatMap(_.set(key, value).toScala.failWith(s"Redis 'SET' operation failed for [key:$key value:$value]"))

  def mset(map: Map[K, V]): Future[Done] =
    redisAsyncCommands.flatMap(_.mset(map.asJava).toScala.failWith(s"Redis 'MSET' operation failed for [map: $map]"))

  def setex(key: K, seconds: Long, value: V): Future[Done] =
    redisAsyncCommands.flatMap(
      _.setex(key, seconds, value).toScala.failWith(s"Redis 'SETEX' operation failed for [key: $key, value: $value]")
    )

  def get(key: K): Future[Option[V]] =
    redisAsyncCommands.flatMap(_.get(key).toScala.map(Option(_))).recover {
      case NonFatal(ex) => throw RedisServerNotAvailable(ex.getCause)
    }

  def mget(keys: List[K]): Future[List[RedisResult[K, Option[V]]]] =
    redisAsyncCommands.flatMap(
      _.mget(keys: _*).toScala
        .map(_.asScala.map(kv => RedisResult(kv.getKey, kv.optional().asScala)).toList)
    )

  def keys(key: K): Future[List[K]] = redisAsyncCommands.flatMap(_.keys(key).toScala.map(_.asScala.toList))

  def exists(keys: K*): Future[Boolean] = redisAsyncCommands.flatMap(_.exists(keys: _*).toScala.map(_ == keys.size))

  def del(keys: List[K]): Future[Long] = redisAsyncCommands.flatMap(_.del(keys: _*).toScala.map(_.toLong))

  def pdel(pattern: K): Future[Long] =
    keys(pattern).flatMap(matchedKeys => if (matchedKeys.nonEmpty) del(matchedKeys) else Future.successful(0))

  def publish(key: K, value: V): Future[Long] = redisAsyncCommands.flatMap(_.publish(key, value).toScala.map(_.toLong))

  def quit(): Future[String] = redisAsyncCommands.flatMap(_.quit().toScala)
}
