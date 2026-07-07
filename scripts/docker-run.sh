#!/bin/sh
NAME=operator
ENV_FILE=
METADATA=
WORKSPACE=

if [ "$(docker ps -a | awk '{print $NF}'| grep $NAME)" != "" ]; then
    echo "Found existing docker so attaching to it."
    docker attach $NAME
    exit 0
fi

while [ "$#" -gt 0 ]; do
    case "$1" in
        -h|--help)
            echo "Usage: $0 -e .env-docker [-n docker-name] [-d metadata] [-w workspace]"
            exit 0
            ;;
        -n)
            if [ "$(docker ps -a | awk '{print $NF}'| grep $2)" != "" ]; then
                echo "Found existing docker so attaching to it."
                docker attach $2
                exit 0
            fi
            echo "- docker name is $2."
            NAME=$2
            shift 2
            ;;
        -e)
            if [ ! -f "$2" ]; then
                echo "$2 not found"
                exit 1
            fi
            echo "- env file id loaded from $2."
            ENV_FILE=$2
            shift 2 
            ;;
        -d)
            if [ ! -d "$2" ]; then
                echo "metadata folder $2 not found"
                exit 1
            fi
            echo "- /metadata will be available as read-only from $2"
            METADATA=$2
            shift 2
            ;;
        -w)
            if [ ! -d "$2" ]; then
                echo "workspace folder $2 not found"
                exit 1
            fi
            echo "- /workspace will be available from $2"
            WORKSPACE=$2
            shift 2 
            ;;
        *)
            break
            ;;
    esac
done

if [ "$ENV_FILE" == "" ];then
    echo "Need to provide ENV_FILE"
    exit 1
fi

DOCKER_VAR="run -e UID=$(id -u) -e GID=$(id -g) --env-file $ENV_FILE -ti --rm -w /workspace"

if [ "$NAME" != "" ];then
    DOCKER_VAR+=" --name $NAME"
fi
if [ "$WORKSPACE" != "" ];then
    DOCKER_VAR+=" -v $(realpath $WORKSPACE):/workspace"
fi
if [ "$METADATA" != "" ];then
    DOCKER_VAR+=" -v $(realpath $METADATA):/metadata:ro"
fi

docker $DOCKER_VAR matrix-operator /app/bin/run ${@:1}
