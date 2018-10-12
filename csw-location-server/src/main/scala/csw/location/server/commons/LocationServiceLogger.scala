package csw.location.server.commons

import csw.location.api.commons.Constants
import csw.logging.scaladsl.LoggerFactory

/**
 * All the logs generated from location service will have a fixed componentName, which is the value of [[Constants.LocationService]].
 * The componentName helps in production to filter out logs from a particular component and this case, it helps to filter out logs
 * generated from location service.
 */
private[location] object LocationServiceLogger extends LoggerFactory(Constants.LocationService)
