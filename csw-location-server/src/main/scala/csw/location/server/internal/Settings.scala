package csw.location.server.internal

import com.typesafe.config.Config

class Settings(config: Config) {

  private val clusterSeedConfig = config.getConfig("csw-location-server")

  def clusterPort: Int = clusterSeedConfig.getInt("cluster-port")
  def httpPort: Int    = clusterSeedConfig.getInt("http-port")
}
