# Matrix
AI assistant interface that is embraced [Spring AI](https://docs.spring.io/spring-ai/reference/index.html) + [Spring AI Agent Utils](https://spring-ai-community.github.io/spring-ai-agent-utils/latest-snapshot/).

## Overview
If you're looking for a good AI coding interface, you should go for [Claude Code](https://code.claude.com/docs/en/overview) or [OpenCode](https://opencode.ai).  
If you're mostly working with frontier AI models, you also should just go for [Claude Code](https://code.claude.com/docs/en/overview) or [OpenCode](https://opencode.ai).  
This is an experimental project that can have an easy way to verify context management with SKILL, AGENT and Prompt engineering.  
And if you would like to try some small AI models that can easily fit into a laptop and learn some content engineering basis like me, this is where you can start.  
[LM Studio](https://lmstudio.ai/download) is a very useful tool that can help to check the interaction between the AI model and your AI agents.

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
./scripts/run.sh -m {agent.path.memory}/session-memory-2026-07-09_00-00-00.json
./scripts/run.sh -p prompt/tetris-game.txt
```

### SkillsJars:
[SkillsJars](https://www.skillsjars.com/docs) plugin is included, so you can easily add more SKILLs.
```shell
mvn -f operator skillsjars:extract 
```

