# Applications

@@ toc { .main depth=1 }

@@@ index
* [csw-cluster-seed](../apps/cswclusterseed.md): Start a cluster seed application, required for running the Location Service.
* [csw-admin-server](../apps/cswadminserver.md): Start HTTP server that supports changing/getting log level of component.
* [csw-location-agent](../apps/cswlocationagent.md): Application used to start non-CSW services so that register with Location Service.
* [csw-config-server](../apps/cswonfigserverapp.md): Start a Configuration Service SVN repository.
* [csw-config-cli](../apps/cswconfigcli.md): Application to perform administrative functions on the Configuration Service.
* [csw-event-cli](../apps/csweventcli.md): Application to publish an event or subscribe to an event.
* [csw-alarm-cli](../apps/cswalarmcli.md): Application to perform administrative functions on the Alarm Service.
* [csw-host-config](../apps/hostconfig.md): Framework for creating host configuration applications, used to start multiple containers on a machine.
@@@

## Starting apps for development

In order to run a component it is required to run `csw-cluster-seed`. Moreover, even to start event service or configuration service,
`csw-cluster-seed` should be running.

But for developers to start `csw-cluster-seed` first and then configuration service and then event service is a tedious job. So, for
development purpose, it is recommended to use a shell script which will start `csw-cluster-seed` and/or configuration service and/or 
event service and/or alarm service.

Assuming that developer has downloaded `csw-apps-<some-version>.zip` from [csw releases](https://github.com/tmtsoftware/csw/releases)
and unzipped it.

There are three folders, as follows, in `csw-apps-<some-version>`
* bin
* lib
* and conf.

All the shell scripts provided by `csw` reside in `bin` folder. The shell script referred in this segment is named as `csw-services.sh`.
Go to the bin folder and hit `./csw-services.sh --help`. This will list all possible options applicable for the script.

@@@ note { title=Note }

This shell script will start `csw-cluster-seed` as the first step regardless of any options provided. 

@@@

The execution of the script is such that it starts `csw-cluster-seed`, then checks whether to start configuration service from provided
`--config` option, if provided, then starts configuration service. Next, it checks whether to start event service from provided 
`--event` option, if provided, it starts event service. Next, it checks whether to start alarm service from provided 
`--alarm` option, if provided, it starts alarm service.

With this, the component code is now ready to connect to configuration service and event service started via `csw-services.sh`.   


  


 


 