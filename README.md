# Matrix
Agent Cli that is embraced Spring AI + Spring AI Agent Utils.

## Overview
This is an experimental project that can have an easy way to verify context management with SKILL, AGENT and Prompt engineering.

## Quick Start
**1. Set up environment variable:**
Replace variables in .env file
```shell
cp dot.env .env # replace the varibles whatever works for you.
```

**2. Configure your agent:**
```yaml
agent:
  message-window-chat-memory:
    max-messages: 500
  prompt:
    system: ${AGENT_PROMPT_SYSTEM:}
  path:
    memory: ${AGENT_PATH_MEMORY:}
  paths:
    agents: ${AGENT_PATHS_AGENTS:}
    skills: ${AGENT_PATHS_SKILLS:}
```

Run Agent Cli
```yaml
./scripts/run.sh 
./scripts/run.sh help
./scripts/run.sh -s
./scripts/run.sh -m {agent.path.memory}/session-memory-2026-07-05_00-00-00.json
./scripts/run.sh -p prompt/tetris-game.txt
```

**3. SkillsJars:**
[SkillsJars](https://www.skillsjars.com/docs) plugin is included, so you can easily add more SKILLs.
```shell
mvn -f operator skillsjars:extract 
```

