# csw-admin-server

A HTTP server application that supports changing/getting log level of component. 

## Prerequisites

The HTTP csw-admin-server needs to be part of csw-cluster so that it can resolve components for which log level needs to be fetched/changed.
A required check before starting csw-admin-server app is to ensure the csw-cluster is setup and seed nodes are available.
Kindly refer to @ref:[CSW Cluster Seed](../apps/cswclusterseed.md) for more information about cluster-seed setup.

## Command line parameter options

* **`--port`** is an optional parameter. When specified the HTTP server will start on this port. Default is 7878.
* **`--help`** prints the help message.
* **`--version`** prints the version of the application.

## Examples

1. 
```
csw-admin-server
```  
Start HTTP server on default port 7878.
 
2. 
```
csw-admin-server --port 8080
```  
Start HTTP server on port 8080.

3. 
```
csw-admin-server --help
```  
Prints help message

4. 
```
csw-admin-server --version
```    
Prints application version


 ## Accessing csw-admin-server routes via HTTP (curl)
 
 
1. Get LogMetadata : Returns current log level of component along with default, akka and slf4j log levels.
 
 * Request:
 ```
 curl -X GET 'http://10.131.21.184:7878/admin/logging/trombone-hcd-akka/level'
 ```
 
 * Response:
 ```
    {
        "defaultLevel": "DEBUG",
        "akkaLevel": "ERROR",
        "slf4jLevel": "INFO",
        "componentLevel": "DEBUG"
    }
 ```
 
2. Set Log Level : Sets the log level of component and returns with status code 200 OK
 
 * Request:
 ```
 curl -X POST 'http://10.131.21.184:7878/admin/logging/trombone-hcd-akka/level?value=DEBUG'
 ```

@@@ note

 Assuming csw-admin-server is running on IP 10.131.21.184 on port 7878.

@@@
