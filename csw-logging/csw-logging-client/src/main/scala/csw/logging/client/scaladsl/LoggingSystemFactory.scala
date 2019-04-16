package csw.logging.client.scaladsl

import java.net.InetAddress

import akka.actor
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter.UntypedActorSystemOps
import csw.logging.client.internal.LoggingSystem

object LoggingSystemFactory {

  /**
   * The factory used to create the LoggingSystem. `LoggingSystem` should be started once in an app.
   *
   * @note it is recommended to use this method for only testing
   * @return the instance of LoggingSystem
   */
  private[logging] def start(): LoggingSystem =
    new LoggingSystem("foo-name", "foo-version", InetAddress.getLocalHost.getHostName, actor.ActorSystem("logging").toTyped)

  /**
   * The factory used to create the LoggingSystem. `LoggingSystem` should be started once in an app.
   *
   * @param name The name of the logging system. If there is a file appender configured, then a file with this name is
   *             created on local machine.
   * @param version the version of the csw which will be a part of log statements
   * @param hostName the host address which will be a part of log statements
   * @param actorSystem the ActorSystem used to create LogActor from LoggingSystem
   * @return the instance of LoggingSystem
   */
  def start(name: String, version: String, hostName: String, actorSystem: ActorSystem[_]): LoggingSystem =
    new LoggingSystem(name, version, hostName, actorSystem)
}
