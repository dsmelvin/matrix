#!/bin/bash
# Starts the shell sandbox playground: a local web UI to send commands to the Bash tool (ShellTools) with an env file,
# to verify what the sandbox allows on this machine (macOS Seatbelt / Linux bubblewrap).
#
#   scripts/sandbox-ui.sh [-e|--env-file <file>] [-w|--workspace <dir>] [--port 8765]
#
# The arguments mean the same as for run.sh. Open the URL that is printed (it contains a one-time token).
SCRIPT_DIR="$(cd "$(dirname "$(realpath "$0")")" && pwd)"
BASEDIR="$(dirname "$SCRIPT_DIR")"
POM=$BASEDIR/operator/pom.xml
LAUNCH_DIR=$(pwd)
ARGS=""

while [ "$#" -gt 0 ]; do
    ARGS+=" $1"
    shift 1
done

mvn -q -f "$POM" compile spring-boot:run \
    -Dspring-boot.run.main-class=guru.kumo.operator.tool.ShellToolsPlayground \
    -Dspring-boot.run.jvmArguments="--enable-native-access=ALL-UNNAMED" \
    -Dspring-boot.run.workingDirectory="$LAUNCH_DIR" \
    -Dspring-boot.run.arguments="$ARGS"
