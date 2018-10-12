Example Code for Common Software Services
==============

This package contains example code that demonstrates the usage of the various Common Software services.
This package will evolve as each system is developed.

Currently, there is example code for the following services:
  * Location Service

Before running the examples here, 
make sure to set the necessary environment variables and start the location service cluster.
For example:

```bash
export interfaceName=enp0s31f6
export clusterSeeds=192.168.178.66:7777

csw-location-server --clusterPort 7777
```

Replace the value for interfaceName with the network interface name you want to use 
(`ifconfig -a` lists the networks on your system).
And replace the IP address and port with your IP address and the port where the location service cluster should run.
Note that the same two environment variables should always be set before running any applications that use the 
location service.

