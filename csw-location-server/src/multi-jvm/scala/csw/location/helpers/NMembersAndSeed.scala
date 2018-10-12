package csw.location.helpers

import akka.actor.ActorSystem
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import com.typesafe.config.{Config, ConfigFactory}
import csw.location.api.commons.{ClusterAwareSettings, ClusterSettings}
import org.jboss.netty.logging.{InternalLoggerFactory, Slf4JLoggerFactory}

class NMembersAndSeed(n: Int) extends MultiNodeConfig {

  val settings = ClusterAwareSettings

  // Fix to avoid 'java.util.concurrent.RejectedExecutionException: Worker has already been shutdown'
  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)

  def makeSystem(config: Config): ActorSystem = ActorSystem(settings.clusterName, config)

  val seed: RoleName = addRole("seed")(settings.onPort(3552))

  val members: Vector[RoleName] = (1 to n).toVector.map { x =>
    addRole(s"member-$x")(settings.joinLocal(3552))
  }

  commonConfig(ConfigFactory.parseString("akka.loggers = [csw.logging.compat.AkkaLogger]"))

  private def addRole(name: String)(settings: ClusterSettings): RoleName = {
    val node = role(name)
    nodeConfig(node)(settings.withEntries(sys.env).config)
    node
  }
}

class OneMemberAndSeed extends NMembersAndSeed(1) {
  val Vector(member) = members
}

class TwoMembersAndSeed extends NMembersAndSeed(2) {
  val Vector(member1, member2) = members
}
