#!/bin/bash
# Thin launcher. The env file (-e/--env-file) and the workspace (-w/--workspace) are handled by the operator itself
# (see EnvFileLoader / OperatorShellCommand), so every argument is simply forwarded.
SCRIPT_DIR="$(cd "$(dirname "$(realpath "$0")")" && pwd)"
export BASEDIR="$(dirname "$SCRIPT_DIR")"
export POM=$BASEDIR/operator/pom.xml
LAUNCH_DIR=$(pwd)
ARGS=""

while [ "$#" -gt 0 ]; do
    case "$1" in
        help|--help|-h)
            mvn -q -f $POM spring-boot:run -Dspring-boot.run.arguments="help operator"
            exit
            ;;
        -i)
            ARGS+=" $1"
            shift 1
            ARGS+=" '\"$1\"'"
            shift 1
            ;;
        *)
            ARGS+=" $1"
            shift 1
            ;;
    esac
done

# "operator" is the default command, so it doesn't need to be named here.
MVN_RUN="$ARGS"
mvn -q -f $POM spring-boot:run -Dspring-boot.run.jvmArguments="--enable-native-access=ALL-UNNAMED" -Dspring-boot.run.workingDirectory=$LAUNCH_DIR -Dspring-boot.run.arguments="$MVN_RUN"
