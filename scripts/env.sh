#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "$(realpath "$0")")" && pwd)"
export BASEDIR="$(dirname "$SCRIPT_DIR")"
if [ -f ".env" ]; then
  . $BASEDIR/.env
fi
