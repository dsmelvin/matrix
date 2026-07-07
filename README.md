# 🕶️ Matrix

> **A lightweight, modular Autonomous AI Agent framework built on Java 21 & Spring AI
2.x.**
> Designed for local LLMs, hierarchical subagent orchestration, dynamic skills, and omnichannel interactions (CLI,
> Discord, Telegram).

## 🌟 Why Matrix?

Most autonomous agent frameworks are built in Python or TypeScript, making integration into enterprise Java environments
complex. **Matrix** brings cutting-edge agentic workflows into modern Java:

- 🧠 **Local-LLM First**: Optimized for small, laptop-friendly models (Qwen, Llama via LM Studio/Ollama) with surgical
  context management, while also supporting frontier models (OpenAI, Google Gemini).
- 🤖 **Subagents & Delegation**: Delegate complex tasks to specialized agents (`plan-agent`, `explore-agent`,
  `bash-agent`) running synchronously or asynchronously in the background.
- 🧩 **Dynamic Skills Engine**: Load on-demand capabilities and domain-specific knowledge packages defined entirely in
  clean Markdown and scripts.
- 🔌 **Model Context Protocol (MCP)**: Native client support for expanding toolsets using the standard MCP ecosystem.
- 💬 **Omnichannel by Design**: Interact with your agents via an interactive CLI, Discord server, or Telegram bot
  simultaneously.
- 🛠️ **Batteries-Included Tools**: Comprehensive built-in tools for bash execution, file manipulation, web extraction,
  task tracking, and vision analysis without heavy external dependencies.

---

## 🚀 Quick Start

### 1. Prerequisites

- **JDK 21** or later
- **Maven 3.9+**
- Local LLM engine (e.g., [llama.cpp](https://llama.app/), [LM Studio](https://lmstudio.ai/), [Ollama](https://ollama.com/)) or an OpenAI / Gemini API
  key

### 2. Configuration

Copy the sample environment file:

```bash
cp dot.env .env
```

Configure your model endpoint and settings in `.env`:

```properties
INFERENCE_MODEL=unsloth/Qwen3.5-9B-GGUF:UD-Q4_K_XL
INFERENCE_BASE_URL=http://127.0.0.1:8080/v1
INFERENCE_API_KEY=

AGENT_MAX_MESSAGE=1000
AGENT_PATH_MEMORY=workspace/memory
AGENT_PATHS_AGENTS=metadata/agents
AGENT_PATHS_SKILLS=metadata/skills
MCP_SERVERS_CONFIGURATION=metadata/mcp-servers.json
BUILT_IN_TOOLS=Bash,BashOutput,DiscordEditMessage,DiscordGetSelfInfo,DiscordReplyMessage,DiscordSendMessage,Edit,Glob,Grep,ImageReader,KillShell,Read,Skill,Task,TaskOutput,TodoWrite,WebFetch,Write

# Optional: Channels
CHANNEL_DISCORD_TOKEN=
CHANNEL_DISCORD_CHANNEL_ID=

CHANNEL_TELEGRAM_TOKEN=
CHANNEL_TELEGRAM_USER_ID=
```

### 3. Run the CLI

```bash
# Start standard CLI
./scripts/run.sh

# Work with different env file or workspace
./scripts/run.sh dot.env workspace

# Start with a specific system prompt
./scripts/run.sh -s metadata/system/OPERATOR_SYSTEM_PROMPT.md

# Resume a previous chat session memory
./scripts/run.sh -m workspace/memory/session-memory-2026-07-09_00-00-00.json

# Execute a one-off prompt file
./scripts/run.sh -u metadata/prompt/tetris-game.txt -1
```

### 4. Run with Docker

```bash
./scripts/docker-build.sh
./scripts/docker-run.sh
```

---

## 🧩 Defining Custom Agents & Skills
Remove "Task" or "Skill" from "BUILT_IN_TOOLS" list if you don't want to trigger Subagents or Skills.

### 🧰 Built-in SubAgent
- bash-agent (tools: Bash,BashOutput,KillShell)
- plan-agent (tools: TodoWrite,WebFetch,Write)
- explore-agent (tools: Edit,Glob,Grep,ImageReader,Read,WebFetch)
- general-purpose-agent (tools: all built-in tools available)

### 🤖 Creating a Subagent

SubAgents are declared in simple Markdown files under `metadata/agents/`:  
- SubAgent Spec without "tools" will access to all built-in tools.
- Skills that are mentioned in the Agent Spec will be preloaded and the rest of the Skills should be available to the subagent as well.

```markdown
---
name: code-reviewer
description: Reviews code for quality and best practices
tools: Read, Glob, Grep
skills:
  - api-conventions
  - error-handling-patterns
---
```
### 🧰 Built-in Skills
- discord
- karpathy-guidelines

### 📦 Creating a Skill

Skills encapsulate specialized domain instructions, references, and executable scripts:

```
metadata/skills/my-skill/
├── SKILL.md                 # Entry point instructions and triggers
├── reference.md             # Domain knowledge
└── scripts/
    └── run_audit.py         # Supporting tool script
```

---

## 🧰 Built-in Tool Ecosystem
"Bash / BashOutput / KillShell" are very powerful tool.  
Don't include them in the "BUILT_IN_TOOLS" list and remove "bash-agent" as well if you have concerns.

| Tool                                | Purpose                                                                           |
|:------------------------------------|:----------------------------------------------------------------------------------|
| `Task` / `TaskOutput`               | Subagent that has its own context window and can be put in the background         |
| `Skill`                             | Skill                                                                             |
| `Bash` / `BashOutput` / `KillShell` | Executes terminal commands and manages background processes.                      |
| `Read` / `Write` / `Edit`           | Precise file inspection and surgical string replacements.                         |
| `Glob` / `Grep`                     | Pure Java file discovery and regular expression searching.                        |
| `TodoWrite`                         | Structured multi-step task planning with status validation.                       |
| `WebFetch`                          | Web content retrieval with HTML-to-Markdown conversion.                           |
| `ImageReader`                       | Multimodal local image and screenshot analysis.                                   |
| `DiscordEditMessage` / `DiscordGetSelfInfo` / `DiscordReplyMessage` / `DiscordSendMessage` | Discord communication. |

---
