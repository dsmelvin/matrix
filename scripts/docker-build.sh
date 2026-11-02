#!/bin/sh
# Usage: docker-build.sh [native]
#   (default) JVM image based on the jar
#   native    GraalVM native-image based image (needs a Docker VM with >= 12GB RAM)
cd "$(dirname "$0")/.." || exit 1
case "$1" in
    native)
        docker buildx build --target native_deploy_image -t matrix-operator --load .
        ;;
    "")
        docker buildx build -t matrix-operator --load .
        ;;
    *)
        echo "Usage: $0 [native]"
        exit 1
        ;;
esac
