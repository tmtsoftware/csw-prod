#!/usr/bin/env bash
#
# Starts services required by CSW and registers them with the location service.
# This script uses the csw-location-agent app to start Event Service and register it with the Location Service
#
# Usage is:
#   ###########################  IMPORTANT ###########################
#    1. Before running this script, make sure that you have interfaceName environment variable is set.
#    2. If env variable is not set, then make sure you pass in --interfaceName or -i <name> to this script to pick the correct
#      interface where location service gets bind.
#   ##################################################################
#
#   If svn repo does not already exists on local machine and interfaceName env variable is set, then use below command to start all the services:
#    csw-services.sh start --initRepo     :to start event service, cluster seed application and register the config and event service
#
#   If svn repo already exists on local machine and interfaceName env variable is set, then use below command to start all the service:
#    csw-services.sh start    :to start event service, cluster seed application and register the config and event service
#
#   If svn repo already exists on local machine and interfaceName env variable is not set, then use below command to start all the service:
#    csw-services.sh start --interfaceName eth0   :to start event service, cluster seed application and register the config and event service
#
#   In case you do not want to start all the services, then specify supported arguments to start
#    csw-services.sh start --seed 5552 --config 5555   :this starts cluster seed on port 5552 and config server on port 5555 (does not start redis)
#
#    csw-services.sh stop     :to stop event service and unregister the services from the location service
#

# Run from the directory containing the script
cd "$( dirname "${BASH_SOURCE[0]}" )"

# Setting default values
seed_port=5552
config_port=5000
sentinel_port=26379
event_master_port=6379
alarm_master_port=7379
initSvnRepo=""

# Always start cluster seed application
shouldStartSeed=true
shouldStartConfig=false
shouldStartEvent=false
shouldStartAlarm=false

script_name=$0

logDir=/tmp/csw/logs
test -d ${logDir} || mkdir -p ${logDir}

# We need at least this version of Redis
minRedisVersion=4.0
redisSentinel=redis-sentinel
redisClient=`echo ${redisSentinel} | sed -e 's/-sentinel/-cli/'`

locationLogFile=${logDir}/location.log
locationPidFile=${logDir}/location.pid

configLogFile=${logDir}/config.log
configPidFile=${logDir}/config.pid

sentinelLogFile=${logDir}/redis_sentinel.log
sentinelPidFile=${logDir}/redis_sentinel.pid
sentinelPortFile=${logDir}/redis_sentinel.port

eventMasterLogFile=${logDir}/event_master.log
eventMasterPidFile=${logDir}/event_master.pid
eventMasterPortFile=${logDir}/event_master.port

alarmMasterLogFile=${logDir}/alarm_master.log
alarmMasterPidFile=${logDir}/alarm_master.pid
alarmMasterPortFile=${logDir}/alarm_master.port

sentinelConf="../conf/redis_sentinel/sentinel.conf"
eventMasterConf="../conf/event_service/master.conf"
alarmMasterConf="../conf/alarm_service/master.conf"

sortVersion="sort -V"

location_agent_script="csw-location-agent"

# Make sure we have the min redis version
function get_version {
    test "$(printf '%s\n' "$@" | ${sortVersion} | head -n 1)" != "$1";
}

function checkIfRedisIsInstalled {
    # Look in the default location first, since installing from the source puts it there, otherwise look in the path
    #if test ! -x ${redisSentinel} ; then redisSentinel=redis-sentinel ; fi
    if ! type ${redisSentinel} &> /dev/null; then
      echo "[ERROR] Can't find $redisSentinel. Please install Redis version [$minRedisVersion] or greater."
      return 1
    else
        redis_version=`${redisSentinel} --version | awk '{sub(/-.*/,"",$3);print $3}' | sed -e 's/v=//'`
        if get_version ${minRedisVersion} ${redis_version}; then
             echo "[ERROR] Required Redis version is [$minRedisVersion], but only version [$redis_version] was found"
             return 1
        else
            return 0
        fi
    fi
}

# Get the ip address associated with provided interface card
function getIP {
    local cmd="ifconfig"

    if [[ `uname` == "Darwin" ]] ; then cmd="ifconfig"
    elif [[ `uname` == "Linux" ]] ; then cmd="ip addr show" ; fi

    local IP=`${cmd} ${interfaceName} | grep 'inet ' | awk '{ print $2}' | cut -d'/' -f1`
    echo ${IP}
}

function isPortProvided {
    re='^[0-9]+$'
    if ! [[ $1 =~ $re ]] ; then return 1; else return 0; fi
}

# Gets a random, unused port
function random_unused_port {
    local port=$(shuf -i 2000-65000 -n 1)
    netstat -lat | grep ${port} > /dev/null
    if [[ $? == 1 ]] ; then
        echo ${port}
    else
        random_unused_port
    fi
}

function start_seed {
    local location_script="csw-location-server"

    if [ -x "$location_script" ]; then
        echo "[LOCATION] Starting cluster seed on port: [$seed_port] ..."
        nohup ./csw-location-server --clusterPort ${seed_port} -DclusterSeeds=${seeds} &> ${locationLogFile} &
        echo $! > ${locationPidFile}
    else
        echo "[ERROR] $location_script script does not exist, please make sure that $location_script resides in same directory as $script_name"
        exit 1
    fi
}

function start_config {
    local config_script="csw-config-server"

    if [ -x "$config_script" ]; then
        echo "[CONFIG] Starting config service on port: [$config_port] ..."
        nohup ./csw-config-server --port ${config_port} ${initSvnRepo} -DclusterSeeds=${seeds} &> ${configLogFile} &
        echo $! > ${configPidFile}
    else
        echo "[ERROR] $config_script script does not exist, please make sure that $config_script resides in same directory as $script_name"
        exit 1
    fi
}

function start_sentinel() {
    if [ -x "$location_agent_script" ]; then
        if checkIfRedisIsInstalled ; then
            echo "Starting Redis Sentinel..."
            nohup ./csw-location-agent -DclusterSeeds=${seeds} --name "EventServer,AlarmServer" --command "$redisSentinel ${sentinelConf} --port ${sentinel_port}" --port "${sentinel_port}"> ${sentinelLogFile} 2>&1 &
            echo $! > ${sentinelPidFile}
            echo ${sentinel_port} > ${sentinelPortFile}
        else
            exit 1
        fi
    else
        echo "[ERROR] $location_agent_script script does not exist, please make sure that $location_agent_script resides in same directory as $script_name"
        exit 1
    fi
}

function start_event() {
    echo "[EVENT] Starting Event Service..."
    start_redis ${eventMasterConf} ${eventMasterLogFile} ${eventMasterPidFile} ${event_master_port} ${eventMasterPortFile}
}

function start_alarm() {
    echo "[ALARM] Starting Alarm Service..."
    start_redis ${alarmMasterConf} ${alarmMasterLogFile} ${alarmMasterPidFile} ${alarm_master_port} ${alarmMasterPortFile}
}

function start_redis() {
    local conf=$1
    local logFile=$2
    local pidFile=$3
    local port=$4
    local portFile=$5

    if [ -x "$location_agent_script" ]; then
        if checkIfRedisIsInstalled ; then
            nohup redis-server ${conf} > ${logFile} 2>&1 &
            echo $! > ${pidFile}
            echo ${port} > ${portFile}
        else
            exit 1
        fi
    else
        echo "[ERROR] $location_agent_script script does not exist, please make sure that $location_agent_script resides in same directory as $script_name"
        exit 1
    fi
}

function enableAllServicesForRunning {
    shouldStartSeed=true
    shouldStartConfig=true
    shouldStartEvent=true
    shouldStartAlarm=true
}

function start_services {
    if [[ "$shouldStartSeed" = true ]]; then start_seed ; fi
    if [[ "$shouldStartConfig" = true ]]; then start_config ; fi
    if [[ "$shouldStartEvent" = true ]]; then start_event; fi
    if [[ "$shouldStartAlarm" = true ]]; then start_alarm; fi
    if [[ ("$shouldStartEvent" = true) || ("$shouldStartAlarm" = true) ]]; then start_sentinel; fi
}

function usage {
    echo
    echo -e "usage: $script_name COMMAND [--seed <port>] [--interfaceName | -i <name>] [--config <port>] [--initRepo] [--event | -es <port>]\n"

    echo "Options:"
    echo "  --seed <seedPort>               start seed on provided port, default: 5552"
    echo "  --interfaceName | -i <name>     start cluster on ip address associated with provided interface, default: en0"
    echo "  --config <configPort>           start http config server on provided port, default: 5000"
    echo "  --initRepo                      create new svn repo, default: use existing svn repo"
    echo -e "  --event | -es <esPort>       start event service on provided port, default: 6379 \n"
    echo -a "  --alarm | -as <asPort>       start alarm service on provided port, default: 7379 \n"

    echo "Commands:"
    echo "  start      Starts all csw services if no options provided"
    echo "  stop       Stops all csw services, it does not take any arguments"
    exit 1
}

function parse_cmd_args {

    case "$1" in
        start)
            shift
            if [[ $# -eq 2 && ($1 == "--interfaceName" || $1 == "-i") ]] ; then
                interfaceName=$2
                # if only interfaceName argument provided with start, then start all services
                enableAllServicesForRunning
            elif [[ $# -eq 1 && $1 == "--initRepo" ]] ; then
                initSvnRepo=$1
                # if only --initRepo argument provided with start, then start all services
                enableAllServicesForRunning
            elif [[ $# -gt 0 ]]; then

                while [[ $# -gt 0 ]]
                do
                    key="$1"

                    case ${key} in
                        --seed)
                            shouldStartSeed=true
                            if isPortProvided $2; then seed_port="$2"; shift; fi
                            ;;
                        --interfaceName | -i)
                            interfaceName="$2"
                            shift
                            ;;
                        --config)
                            shouldStartConfig=true
                            if isPortProvided $2; then config_port="$2"; shift; fi
                            ;;
                        --initRepo)
                            shouldStartConfig=true
                            initSvnRepo=${key}
                            ;;
                        --event | -es)
                            shouldStartEvent=true
                            if isPortProvided $2; then sentinel_port="$2"; shift; fi
                            ;;
                        --alarm | -as)
                            shouldStartAlarm=true
                            if isPortProvided $2; then sentinel_port="$2"; shift; fi
                            ;;
                        --help)
                            usage
                            ;;
                        *)
                            echo "[ERROR] Unknown arguments provided for start command. Find usage below:"
                            usage
                            ;;
                    esac
                    shift
                done
            else
                # if no options provided with start argument, then start all services
                enableAllServicesForRunning
            fi

            if [[ ${interfaceName} == "" ]] ; then
                echo "[ERROR] interfaceName is not provided, please provide valid interface name via argument --interfaceName|-i or set interfaceName env variable."
                exit 1
            else
                # Get the ip address of local machine and store it in variable: my_ip
                IP=$(getIP)

                if [[ ${IP} == "" ]]; then
                    echo "[ERROR] Interface: [$interfaceName] not found, please provide valid interface name."
                    exit 1
                else
                    seeds="${IP}:${seed_port}"
                    echo "[INFO] Using clusterSeeds=$seeds"

                    start_services

                    echo "================================================================="
                    echo "All the logs are stored at location: [$logDir]"
                    echo "================================================================="
                    echo "For assemblies or HCD's to join this cluster and use config and event service from other machine's/nodes, run below command to export env variable:"
                    echo "export clusterSeeds=$seeds"
                    echo "================================================================="
                fi
            fi
            ;;
        stop)
            # Stop Redis
            stop "Redis Sentinel" ${sentinelPidFile} ${sentinelPortFile}
            stop "Event Server" ${eventMasterPidFile} ${eventMasterPortFile}
            stop "Alarm Server" ${alarmMasterPidFile} ${alarmMasterPortFile}

            # Stop Cluster Seed application
            if [ ! -f ${locationPidFile} ]; then
                echo "[LOCATION] Cluster seed $locationPidFile does not exist, process is not running."
            else
                local PID=$(cat ${locationPidFile})
                echo "[LOCATION] Stopping Cluster Seed application..."
                kill ${PID} &> /dev/null
                rm -f ${locationPidFile} ${locationLogFile}
                echo "[LOCATION] Cluster Seed stopped."
            fi

            # Stop Config Service
            if [ ! -f ${configPidFile} ]; then
                echo "[CONFIG] Config Service $configPidFile does not exist, process is not running."
            else
                local PID=$(cat ${configPidFile})
                echo "[CONFIG] Stopping Config Service..."
                kill ${PID} &> /dev/null
                rm -f ${configPidFile} ${configLogFile}
                echo "[CONFIG] Config Service stopped"
            fi
            ;;
        *)
            echo "[ERROR] Please use start or stop as first argument, find usage below: "
            usage
            ;;
    esac
}

function stop() {
 local serviceName=$1
 local pidFile=$2
 local portFile=$3
    if [ ! -f ${pidFile} ]; then
        echo "$serviceName $pidFile does not exist, process is not running."
    else
        local pid=$(cat ${pidFile})
        local port=$(cat ${portFile})
        echo "Stopping $serviceName..."
        ${redisClient} -p ${port} shutdown
        while(test -x /proc/${pid})
        do
            echo "Waiting for $serviceName to shutdown ..."
            sleep 1
        done
        echo "$serviceName stopped."
        rm -f ${portFile} ${pidFile}
    fi
}

# Parse command line arguments
parse_cmd_args "$@"