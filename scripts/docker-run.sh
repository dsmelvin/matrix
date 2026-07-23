#!/bin/sh

DOCKER_VAR="run -ti --rm "
DOCKER_VAR+=" -e INFERENCE_MODEL=qwen/qwen3.5-9b"
DOCKER_VAR+=" -e INFERENCE_BASE_URL=http://host.docker.internal:11434/v1"
DOCKER_VAR+=" -e INFERENCE_API_KEY="
DOCKER_VAR+=" -e AGENT_MAX_MESSAGE=1000"
DOCKER_VAR+=" -e AGENT_MAX_COMPLETION_TOKEN=262144"
DOCKER_VAR+=" -e AGENT_PROMPT_SYSTEM=/workspace/prompt/OPERATOR_SYSTEM_PROMPT_V0.md"
DOCKER_VAR+=" -e AGENT_PATH_MEMORY=/workspace/memory"
DOCKER_VAR+=" -e AGENT_PATHS_AGENTS=/workspace/agents"
DOCKER_VAR+=" -e AGENT_PATHS_SKILLS=/workspace/skills"
DOCKER_VAR+=" -e MCP_SERVERS_CONFIGURATION=/workspace/mcp-servers.json"
DOCKER_VAR+=" -e AGENT_TOOLS=TaskTool,SkillTool,ImageReaderTool,GrepTool,GlobTool,ShellTools,FileSystemTools,ListDirectoryTool,SmartWebFetchTool,TodoWriteTool"
DOCKER_VAR+=" -e UID=$(id -u) -e GID=$(id -g) -w /workspace"

if [ "$1" == "help" ];then
  echo "Need to specify WORKSPACE and will be mount at /workspace inside Docker env."
  echo "Ex: $0 ~/workspace"
elif [ "$1" != "" ] && [ -d $1 ];then
  docker $DOCKER_VAR -v $(realpath $1):/workspace matrix-operator sh
else
  docker $DOCKER_VAR matrix-operator /app/bin/run
fi
