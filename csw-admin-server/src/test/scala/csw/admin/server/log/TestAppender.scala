package csw.admin.server.log

import akka.actor.ActorRefFactory
import csw.logging.RichMsg
import csw.logging.appenders.{LogAppenderBuilder, StdOutAppender}

class TestAppender(callback: Any ⇒ Unit) extends LogAppenderBuilder {

  /**
   * A constructor for the TestAppender class.
   *
   * @param factory    an Akka factory.
   * @param stdHeaders the headers that are fixes for this service.
   * @return the stdout appender.
   */
  def apply(factory: ActorRefFactory, stdHeaders: Map[String, RichMsg]): StdOutAppender =
    new StdOutAppender(factory, stdHeaders, callback)
}
