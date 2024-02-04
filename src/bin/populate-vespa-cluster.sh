#!/bin/bash

# root of the package
PACKAGE_HOME=$(cd "$(dirname "$0")";cd ..;pwd)

# bootstrap class
MAIN_CLASS=com.pehrs.spring.ai.etl.PopulateVespaVectorStore

# classpath
CLASSPATH="${PACKAGE_HOME}/classes:${PACKAGE_HOME}/lib/*"

# JVM startup parameters
#JAVA_OPTS="-server -Xmx2g -Xms2g -Xmn256m -XX:PermSize=128m -Xss256k -XX:+DisableExplicitGC -XX:+UseConcMarkSweepGC -XX:+CMSParallelRemarkEnabled -XX:+UseCMSCompactAtFullCollection -XX:LargePageSizeInBytes=128m -XX:+UseFastAccessorMethods -XX:+UseCMSInitiatingOccupancyOnly -XX:CMSInitiatingOccupancyFraction=70"
JAVA_OPTS="-server -Xmx2g -Xms2g -Xmn256m "


# use the *java* residing in JAVA_HOME
export _EXECJAVA="$JAVA_HOME/bin/java"

echo "-------------------------------------------"
echo "starting server"
echo

echo "MAIN_CLASS:"
echo "      [${MAIN_CLASS}]"
echo

echo "JVM parameter:"
echo "      [${JAVA_OPTS}]"
echo

echo "-------------------------------------------"

# start with *nohup* to prevent the OS from killing our program after logout
$_EXECJAVA $JAVA_OPTS -classpath $CLASSPATH $MAIN_CLASS
