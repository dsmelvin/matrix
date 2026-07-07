#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "$(realpath "$0")")" && pwd)"
export BASEDIR="$(dirname "$SCRIPT_DIR")"
export POM=$BASEDIR/operator/pom.xml
WORKSPACE=$(pwd)
set -a
ENV_CHECK="false"
ARG=""
ARGS=""
while [ "$#" -gt 0 ]; do
    case "$1" in
        help|--help|-h)
            mvn -q -f $POM spring-boot:run -Dspring-boot.run.arguments="help operator"
            exit
            ;;
        -i)
            ARG+=" $1"
            shift 1
            ARG+=" '\"$1\"'"
            shift 1
            ;;
        -*)
            ARG+=" $1"
            shift 1
            ARG+=" $1"
            shift 1
            ;;
        *)
            if [ -f "$1" ]; then
              . $1
              ENV_CHECK="true"
            elif [ -d "$1" ]; then
              WORKSPACE=$(realpath "$1")
            else
              ARGS+=" $1"
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
MVN_RUN="operator $ARG $ARGS"
mvn -q -f $POM spring-boot:run -Dspring-boot.run.jvmArguments="--enable-native-access=ALL-UNNAMED" -Dspring-boot.run.workingDirectory=$WORKSPACE -Dspring-boot.run.arguments="$MVN_RUN"
