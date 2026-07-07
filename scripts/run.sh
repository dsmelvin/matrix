#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "$(realpath "$0")")" && pwd)"
export BASEDIR="$(dirname "$SCRIPT_DIR")"
WORKSPACE=$(pwd)
set -a
ARG=""
ENV_CHECK="false"
while [ "$#" -gt 0 ]; do
    case "$1" in
        help|--help|-h)
            mvn -f $BASEDIR/operator/pom.xml spring-boot:run -Dspring-boot.run.arguments="help operator"
            exit
            ;;
        -*)
            ARG+=" $1"
            shift 1
            ARG+=" $1"
            shift 1
            ;;
        *)
            if [ -f $1 ]; then
              . $1
              ENV_CHECK="true"
            elif [ -d $1 ]; then
              WORKSPACE=$(realpath "$1")
            fi
            shift 1
            ;;
    esac
done
if [ $ENV_CHECK == "false" ] && [ -f ".env" ]; then
  . .env
fi
set +a

cd $(pwd)
if [ "$1" == "help" ]; then
  MVN_RUN="help operator"
  mvn -f $BASEDIR/operator/pom.xml spring-boot:run -Dspring-boot.run.workingDirectory=$WORKSPACE -Dspring-boot.run.arguments="$MVN_RUN"
else
  MVN_RUN="operator $ARG"
  mvn -q -f $BASEDIR/operator/pom.xml spring-boot:run -Dspring-boot.run.jvmArguments="--enable-native-access=ALL-UNNAMED" -Dspring-boot.run.workingDirectory=$WORKSPACE -Dspring-boot.run.arguments="$MVN_RUN"
fi
