#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "$(realpath "$0")")" && pwd)"
export BASEDIR="$(dirname "$SCRIPT_DIR")"
WORKSPACE=$(pwd)
set -a
if [ -f "$1" ]; then
    . $1
else 
    . .env
fi
if [ -d "$2" ]; then
    WORKSPACE=$(realpath "$2")
fi
cd $(pwd)
set +a
if [ "$1" == "help" ]; then
  MVN_RUN="help operator"
  mvn -f $BASEDIR/operator/pom.xml spring-boot:run -Dspring-boot.run.workingDirectory=$WORKSPACE -Dspring-boot.run.arguments="$MVN_RUN"
else
  MVN_RUN="operator ${@:1}"
  mvn -f $BASEDIR/operator/pom.xml spring-boot:run -Dspring-boot.run.workingDirectory=$WORKSPACE -Dspring-boot.run.arguments="$MVN_RUN"
fi
