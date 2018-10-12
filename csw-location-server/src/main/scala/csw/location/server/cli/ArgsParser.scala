package csw.location.server.cli

import csw.services.BuildInfo
import scopt.OptionParser

/**
 * Parses the command line options using `scopt` library.
 */
class ArgsParser(name: String) {

  private val parser: OptionParser[Options] = new scopt.OptionParser[Options](name) {
    head(name, BuildInfo.version)

    opt[Int]("clusterPort") action { (x, c) =>
      c.copy(clusterPort = Some(x))
    } text "Optional: Port at which this cluster seed will run. Default is 3552"

    opt[Unit]("testMode") action { (_, c) =>
      c.copy(testMode = true)
    } text "Optional: if provided, start cluster seed app with default port: 3552 and self join to form single node cluster. [Only for testing purpose]"

    help("help")

    version("version")
  }

  def parse(args: Seq[String]): Option[Options] = parser.parse(args, Options())
}
