#!/bin/bash

# root of the package
PACKAGE_HOME=$(cd "$(dirname "$0")";cd ..;pwd)

MAIN_CLASS=com.pehrs.spring.ai.service.RagSampleService

# check whether the program has been started
PIDs=`jps -l | grep $MAIN_CLASS | awk '{print $1}'`
if [ -n "${PIDs}" ]; then
    echo "failed to start. The program is already running. PID:${PIDs}"
    exit 1
fi

# classpath
CLASSPATH="${PACKAGE_HOME}/classes:${PACKAGE_HOME}/lib/*"

# JVM startup parameters
#JAVA_OPTS="-server -Xmx2g -Xms2g -Xmn256m -XX:PermSize=128m -Xss256k -XX:+DisableExplicitGC -XX:+UseConcMarkSweepGC -XX:+CMSParallelRemarkEnabled -XX:+UseCMSCompactAtFullCollection -XX:LargePageSizeInBytes=128m -XX:+UseFastAccessorMethods -XX:+UseCMSInitiatingOccupancyOnly -XX:CMSInitiatingOccupancyFraction=70"
JAVA_OPTS="-server -Xmx2g -Xms2g -Xmn256m"

# use the *java* residing in JAVA_HOME
export _EXECJAVA="$JAVA_HOME/bin/java"

echo "MAIN_CLASS:"
echo "      [${MAIN_CLASS}]"
echo

echo "JVM parameter:"
echo "      [${JAVA_OPTS}]"
echo

$_EXECJAVA $JAVA_OPTS -classpath $CLASSPATH $MAIN_CLASS
