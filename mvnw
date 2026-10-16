#!/bin/sh
# ----------------------------------------------------------------------------
# Apache Maven Wrapper Startup Batch Script
# ----------------------------------------------------------------------------

set -e

WRAPPER_JAR=".mvn/wrapper/maven-wrapper.jar"
WRAPPER_LAUNCHER="org.apache.maven.wrapper.MavenWrapperMain"

# Find directory containing this script
PRG="$0"
while [ -h "$PRG" ]; do
  ls=`ls -ld "$PRG"`
  link=`expr "$ls" : '.*-> \(.*\)$'`
  if expr "$link" : '/.*' > /dev/null; then
    PRG="$link"
  else
    PRG=`dirname "$PRG"`/"$link"
  fi
done
BASE_DIR=`dirname "$PRG"`

# Check JAVA_HOME
if [ -z "$JAVA_HOME" ]; then
  JAVACMD="java"
else
  JAVACMD="$JAVA_HOME/bin/java"
fi

# Download maven-wrapper.jar if not present
if [ ! -f "$BASE_DIR/$WRAPPER_JAR" ]; then
  mkdir -p "$BASE_DIR/.mvn/wrapper"
  JAR_URL="https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.2.0/maven-wrapper-3.2.0.jar"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$BASE_DIR/$WRAPPER_JAR" "$JAR_URL" || true
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$BASE_DIR/$WRAPPER_JAR" "$JAR_URL" || true
  fi
fi

if [ -f "$BASE_DIR/$WRAPPER_JAR" ]; then
  exec "$JAVACMD" -classpath "$BASE_DIR/$WRAPPER_JAR" \
    "-Dmaven.multiModuleProjectDirectory=$BASE_DIR" \
    $WRAPPER_LAUNCHER "$@"
else
  # Fallback to system mvn if wrapper download fails or offline
  if command -v mvn >/dev/null 2>&1; then
    exec mvn "$@"
  else
    echo "Error: Neither Maven wrapper jar nor system 'mvn' was found." >&2
    exit 1
  fi
fi
