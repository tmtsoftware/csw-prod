package csw.services.event.javadsl

import java.util.concurrent.CompletableFuture

import akka.stream.Materializer
import csw.services.event.scaladsl.RedisFactory

import scala.compat.java8.FutureConverters.FutureOps
import scala.concurrent.ExecutionContext

class JRedisFactory(redisFactory: RedisFactory)(implicit ec: ExecutionContext, mat: Materializer) {

  def publisher(host: String, port: Int): IEventPublisher = redisFactory.publisher(host, port).asJava
  def publisher(): CompletableFuture[IEventPublisher]     = redisFactory.publisher().map(_.asJava).toJava.toCompletableFuture

  def subscriber(host: String, port: Int): IEventSubscriber = redisFactory.subscriber(host, port).asJava
  def subscriber(): CompletableFuture[IEventSubscriber]     = redisFactory.subscriber().map(_.asJava).toJava.toCompletableFuture
}
