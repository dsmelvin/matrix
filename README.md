# Matrix
Agent Cli that is embraced Spring AI + Spring AI Agent Utils.

## Overview
This is an experimental project that can have an easy way to verify context management with SKILL, AGENT and Prompt engineering.

## Quick Start
### Set up environment variable: Replace variables in .env file
```shell
cp dot.env .env # replace the variables whatever works for you.
```

### run Agent cli
```yaml
./scripts/run.sh 
./scripts/run.sh help
./scripts/run.sh -s
./scripts/run.sh -m {agent.path.memory}/session-memory-2026-07-05_00-00-00.json
./scripts/run.sh -p prompt/tetris-game.txt
```

### SkillsJars:
[SkillsJars](https://www.skillsjars.com/docs) plugin is included, so you can easily add more SKILLs.
```shell
mvn -f operator skillsjars:extract 
```

