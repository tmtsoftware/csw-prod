Location Service
================

The Location Service helps accomplish service discovery i.e. automatic detection of components and services in the TMT computer network.
It uses CRDT (Conflict Free Replicated Data) on the akka cluster as the service registry.

Using location service, a component or service can:
* Register its location information.
* Find or Resolve the location of another component or service using its name.
* List or filter components and services using subsystem name/prefix in its name.
* Track the location of another component or service for change in its location information.

If you want to get started with location service, refer the [examples](https://tmtsoftware.github.io/csw/services/location.html).

You can refer to Scala documentation [here](https://tmtsoftware.github.io/csw/api/scala/csw/location/index.html).

You can refer to Java documentation [here](https://tmtsoftware.github.io/csw/api/java/?/index.html).