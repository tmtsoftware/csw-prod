# Location Service

The Location Service handles component (i.e., Applications, Sequencers, Assemblies, HCDs, and Services) registration 
and discovery in the distributed TMT software system.

CSW Location Service cluster seed must be running, and appropriate environment variables set to run apps.
See @ref:[CSW Cluster Seed](../apps/cswclusterseed.md).

A component’s location information can be used by other components and services to connect to it and use it. 
An example of location information is:
 
* host address/port pairs
* URL/URIs paths
* connection protocols
* log-admin actor reference

@@@ note { title="async handling in scala and java examples." }

 * **Scala:** `async` marks a block of asynchronous code and allows to `await` the computation till the Future is complete.
      For more info, please refer to: https://github.com/scala/async
 
 * **Java non-blocking example:** The code snippets use `CompletableFuture` and its `thenAsync`, `thenApply` methods. This style allows to compose multiple Futures and not block the calling thread till Futures are complete. 

 * **Java blocking example:** The code snippets use `CompletableFuture` using `get` blocking call. This style blocks the calling thread till the Future is complete.
    
@@@

## Dependencies

To use the Location Service without using the framework, add this to your `build.sbt` file:

sbt
:   @@@vars
    ```scala
    libraryDependencies += "com.github.tmtsoftware.csw" %% "csw-location-server" % "$version$"
    ```
    @@@


## Create Location Service

Note that before using this API, the [csw-cluster-seed](../apps/cswclusterseed.html) application 
(the main seed node for the location service cluster) should be running at a known location in the 
network (or at multiple locations) and the necessary configuration, environment variables or system 
properties should be defined to point to the correct host and port number(s).

`LocationServiceFactory` provides a make method to create an instance of the LocationService API. 
This call will look for configuration or environment variable settings as described 
here: @scaladoc[ClusterSettings](csw/location/commons/ClusterSettings).

Scala
:   @@snip [LocationServiceExampleClientApp.scala](../../../../examples/src/main/scala/csw/location/LocationServiceExampleClientApp.scala) { #create-location-service }

Java
:   @@snip [JLocationServiceExampleClient.java](../../../../examples/src/main/java/csw/location/JLocationServiceExampleClient.java) { #create-location-service }


## Creating Components, Connections and Registrations

An Application, Sequencer, Assembly, HCD, or Service component may need to be used by another component as part of normal observatory operations. 
It must register its location information with Location Service so that other components can find it. A location information comprises of:

* **ComponentId** : A component id comprises of 
    * **ComponentName** : a name depicting the component.
    * **ComponentType** : such as Container, Sequencer, HCD, Assembly, Service.
   
* **ConnectionType** : the means to reach components. These are categorized as `Akka`, `HTTP`, or `Tcp` type connections.

The location information is stored in Location Service as **Registrations**.

Some of the examples of string representation of a connection are:
 
* TromboneAssembly-assembly-akka 
* TromboneHcd-hcd-akka 
* ConfigServer-service-http 
* EventService-service-tcp

The `register` API takes a `Registration` parameter and returns a future registration result. 
If registration fails for some reason, the returned future will fail with an exception. 
(Registration will fail if the `csw-cluster-seed` application is
not running or could not be found or if the given component name was already registered.)

The following example shows registration of both UnTyped ActorRef and Typed ActorRef:
 
Scala
:   @@snip [LocationServiceExampleClientApp.scala](../../../../examples/src/main/scala/csw/location/LocationServiceExampleClientApp.scala) { #Components-Connections-Registrations }

Java
:   @@snip [JLocationServiceExampleClient.java](../../../../examples/src/main/java/csw/location/JLocationServiceExampleClient.java) { #Components-Connections-Registrations }

@@@ note

The `AkkaRegistration` api takes only Typed ActorRefs. Hence, to register an UnTyped ActorRef for an akka connection, it needs to be
adapted to Typed `ActorRef[Nothing]`. This can be achieved using adapter provided for scaladsl and javadsl. The usage of adapter is
shown in above snippet for scala and java both. 

Also, note that for components, the registration will be taken care of via `csw-framework`. Hence, component developers won't register any connections during their development.
So, above demonstration of registering connections is for explanatory and testing purpose only.  
@@@

## Creating ActorRef for Registration

Make sure the ActorSystem used to start actors using the location service is created using `ActorSystemFactory` as follows:
 

Scala
:  @@snip [LocationServiceExampleClientApp.scala](../../../../examples/src/main/scala/csw/location/LocationServiceExampleClientApp.scala) { #create-actor-system }

Java
:  @@snip [JLocationServiceExampleClient.java](../../../../examples/src/main/java/csw/location/JLocationServiceExampleClient.java) { #create-actor-system }

This is required to start a remote ActorSystem on the same network interface where the csw-cluster is running. 
All the ActorRefs created using this ActorSystem will be available for communication from other components 
that are part of csw-cluster.

## Resolving Connections

The `list` API returns a list of the currently registered connections from the Location Service.
  
A connection of interest can be looked up using the `resolve` or `find` methods:   

`resolve` gets the location for a connection from the local cache. 
If not found in the cache, it waits for the event to arrive within the specified time limit and returns None on failure.    

`find` returns the location for a connection from the local cache and returns None if not found there.    

Scala
:   @@snip [LocationServiceExampleClientApp.scala](../../../../examples/src/main/scala/csw/location/LocationServiceExampleClientApp.scala) { #find }

Java
:   @@snip [JLocationServiceExampleClient.java](../../../../examples/src/main/java/csw/location/JLocationServiceExampleClient.java) { #find }

The logging output from the above example when the given component is not registered should include:

```
[INFO] Attempting to find connection AkkaConnection(ComponentId(LocationServiceExampleComponent,Assembly))
[INFO] Result of the find call: None 
```

An example of the resolve command is shown in the following: 

Scala
:   @@snip [LocationServiceExampleClientApp.scala](../../../../examples/src/main/scala/csw/location/LocationServiceExampleClientApp.scala) { #resolve }

Java
:   @@snip [JLocationServiceExampleClient.java](../../../../examples/src/main/java/csw/location/JLocationServiceExampleClient.java) { #resolve }

The logging output from the above example should include:

```
[INFO] Attempting to resolve AkkaConnection(ComponentId(LocationServiceExampleComponent,Assembly)) with a wait of 30 seconds ...
```

If you then start the LocationServiceExampleComponentApp, the following message will be logged:
```
[INFO] Resolve result: LocationServiceExampleComponent-assembly-akka, component type=Assembly, connection type=AkkaType
```

If not, eventually the operation will timeout and the output should read:
```
[INFO] Timeout waiting for location AkkaConnection(ComponentId(LocationServiceExampleComponent,Assembly)) to resolve.
```

@@@ note

The `resolve` and `find` api returns the concrete `Location` type i.e. `Akkalocation`, `HttpLocation` or `TcpLocation` as demonstrated in this section. Once the akka location
is found or resolved, we need to retain the type to the actorRef, since the explicit type annotation is removed from the program, before it is executed at run-time 
(refer [type erasure](https://en.wikipedia.org/wiki/Type_erasure)). Use following `AkkaLocation` api to get the correct Typed ActorRef:

Scala
:   @@snip [LocationServiceExampleClientApp.scala](../../../../examples/src/main/scala/csw/location/LocationServiceExampleClientApp.scala) { #typed-ref }

Java
:   @@snip [JLocationServiceExampleClient.java](../../../../examples/src/main/java/csw/location/JLocationServiceExampleClient.java) { #typed-ref }

@@@ 
## Filtering

The `list` API and its variants offer means to inquire about available connections with the Location Service. 
The **parameter-less** `list` returns all available connections

Scala
:   @@snip [LocationServiceExampleClientApp.scala](../../../../examples/src/main/scala/csw/location/LocationServiceExampleClientApp.scala) { #list }

Java
:   @@snip [JLocationServiceExampleClient.java](../../../../examples/src/main/java/csw/location/JLocationServiceExampleClient.java) { #list }

The log output from the above should contain:

```
[INFO] All Registered Connections:
[INFO] --- configuration-service-http, component type=Service, connection type=HttpType
[INFO] --- hcd1-hcd-akka, component type=HCD, connection type=AkkaType
[INFO] --- LocationServiceExampleComponent-assembly-akka, component type=Assembly, connection type=AkkaType
[INFO] --- redis-service-tcp, component type=Service, connection type=TcpType
[INFO] --- assembly1-assembly-akka, component type=Assembly, connection type=AkkaType
```
Other variants are filters using `ConnectionType`, `ComponentType`, and `hostname`.

Filtering by component type is shown below:

Scala
:   @@snip [LocationServiceExampleClientApp.scala](../../../../examples/src/main/scala/csw/location/LocationServiceExampleClientApp.scala) { #filtering-component }

Java
:   @@snip [JLocationServiceExampleClient.java](../../../../examples/src/main/java/csw/location/JLocationServiceExampleClient.java) { #filtering-component }

The log output from the above code should contain:

```
[INFO] Registered Assemblies:
[INFO] --- LocationServiceExampleComponent-assembly-akka, component type=Assembly, connection type=AkkaType
[INFO] --- assembly1-assembly-akka, component type=Assembly, connection type=AkkaType
```

Filtering by connection type is shown below:

Scala
:   @@snip [LocationServiceExampleClientApp.scala](../../../../examples/src/main/scala/csw/location/LocationServiceExampleClientApp.scala) { #filtering-connection }

Java
:   @@snip [JLocationServiceExampleClient.java](../../../../examples/src/main/java/csw/location/JLocationServiceExampleClient.java) { #filtering-connection }

The log output should contain:

```
[INFO] Registered Akka connections:
[INFO] --- hcd1-hcd-akka, component type=HCD, connection type=AkkaType
[INFO] --- LocationServiceExampleComponent-assembly-akka, component type=Assembly, connection type=AkkaType
[INFO] --- assembly1-assembly-akka, component type=Assembly, connection type=AkkaType
```

Filtering akka connections by prefix is shown below:

Scala
:   @@snip [LocationServiceExampleClientApp.scala](../../../../examples/src/main/scala/csw/location/LocationServiceExampleClientApp.scala) { #filtering-prefix }

Java
:   @@snip [JLocationServiceExampleClient.java](../../../../examples/src/main/java/csw/location/JLocationServiceExampleClient.java) { #filtering-prefix }

The log output should contain:

```
[INFO] Registered akka locations for nfiraos.ncc
[INFO] --- hcd1-hcd-akka, component type=HCD, connection type=AkkaType
[INFO] --- assembly1-assembly-akka, component type=Assembly, connection type=AkkaType
```

## Tracking and Subscribing

The lifecycle of a connection of interest can be followed using either the `track` API or the `subscribe` API.  

These methods take a `Connection` instance as a parameter. **A `Connection` need not already be registered with Location Service.** 
It's alright to track connections that will be registered in future. 

A `track` API returns two values:     
* A **source** that will emit a stream of `TrackingEvents` for the connection.  
* A **Killswitch** to turn off the stream when no longer needed.  

The Akka stream API provides many building blocks to process this stream, such as Flow and Sink. 
In the example below, `Sink.actorRef` is used to forward any location messages received to the current actor (self).

A consumer can shut down the stream using the Killswitch.


The `subscribe` API allows the caller to track a connection and receive the TrackingEvent notifications via a callback. 

The API expects following parameters :    
* An existing connection or a connection to be registered in the future.  
* A callback that implements `Consumer`, receives the TrackEvent as a parameter.  
 
In return it gives a Killswitch that can be used to turn off the event notifications and release the supplied callback, if required.
 

Scala
:   @@snip [LocationServiceExampleClientApp.scala](../../../../examples/src/main/scala/csw/location/LocationServiceExampleClientApp.scala) { #tracking }

Java
:   @@snip [JLocationServiceExampleClient.java](../../../../examples/src/main/java/csw/location/JLocationServiceExampleClient.java) { #tracking }

The log output should contain the following:
```
[INFO] Starting to track AkkaConnection(ComponentId(LocationServiceExampleComponent,Assembly)) LocationUpdated(HttpLocation(HttpConnection(ComponentId(configuration,Service)),http://131.215.210.170:8080/path123))
[INFO] Starting a subscription to AkkaConnection(ComponentId(LocationServiceExampleComponent,Assembly))
[INFO] Subscribing to connection LocationServiceExampleComponent-assembly-akka
[INFO] Location updated LocationServiceExampleComponent-assembly-akka, component type=Assembly, connection type=AkkaType
[INFO] subscription event
[INFO] Location updated LocationServiceExampleComponent-assembly-akka, component type=Assembly, connection type=AkkaType
```

If you now stop the LocationServiceExampleComponentApp, it would log:
```
[INFO] subscription event
[INFO] Location removed AkkaConnection(ComponentId(LocationServiceExampleComponent,Assembly))
[INFO] Location removed AkkaConnection(ComponentId(LocationServiceExampleComponent,Assembly))
```

If you start the LocationServiceExampleComponentApp again, the log output should be:
```
[INFO] subscription event
[INFO] Location updated LocationServiceExampleComponent-assembly-akka, component type=Assembly, connection type=AkkaType
[INFO] Location updated LocationServiceExampleComponent-assembly-akka, component type=Assembly, connection type=AkkaType
```

Note: the line after the words "subscription event" in our example is generated by the subscription, and the other line is from
tracking.  These two events could come in any order.


## Unregistering

One of the ways to `unregister` a service is by calling unregister on registration result received from `register` API.

Scala
:   @@snip [LocationServiceExampleClientApp.scala](../../../../examples/src/main/scala/csw/location/LocationServiceExampleClientApp.scala) { #unregister }

Java
:   @@snip [JLocationServiceExampleClient.java](../../../../examples/src/main/java/csw/location/JLocationServiceExampleClient.java) { #unregister }


## Source code for examples

* @github[Scala Example](/examples/src/main/scala/csw/location/LocationServiceExampleClientApp.scala)
* @github[JavaBlocking Example](/examples/src/main/java/csw/location/JLocationServiceExampleClient.java)
