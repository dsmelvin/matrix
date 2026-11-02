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
MAIN_AGENT_TOOLBOX=Bash,BashOutput,DiscordEditMessage,DiscordGetSelfInfo,DiscordReplyMessage,DiscordSendMessage,Edit,Git,Glob,Grep,ImageReader,KillShell,Read,Skill,Task,TaskOutput,TodoWrite,WebFetch,Write

# Optional: Bash tool sandbox (disabled automatically inside Docker). Lists are separated by ","
# AGENT_SHELL_SANDBOX_MODE=required                  # required | auto | off
# AGENT_SHELL_SANDBOX_WRITABLE_PATHS=~/.m2,~/.npm    # extra writable directories
# AGENT_SHELL_SANDBOX_DENY_PATHS=~/Documents         # neither readable nor writable
# AGENT_SHELL_SANDBOX_DENY_FILE_NAMES=.env,*.pem     # denied wherever they are (wildcards * and ?)
# AGENT_SHELL_SANDBOX_PASS_ENV=GH_TOKEN,NPM_TOKEN    # env variables handed to commands
# AGENT_SHELL_SANDBOX_ALLOW_NETWORK=true             # true | false
# AGENT_SHELL_SANDBOX_ALLOWED_HOSTS=github.com,*.githubusercontent.com   # only these hosts (HTTP/HTTPS); overrides ALLOW_NETWORK=true

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
./scripts/run.sh -e dot.env -w workspace

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

### 5. Build a GraalVM native image (optional)

Requires **GraalVM 25** (JDK with `native-image`) as `JAVA_HOME`.

```bash
mvn -Pnative -DskipTests clean package
./operator/target/operator operator   # same arguments as the jar
```

The native build runs Spring AOT processing and compiles `operator/target/operator`, which starts in a fraction
of a second. Extra reflection/resource hints live in
`operator/src/main/java/guru/kumo/operator/configuration/NativeImageRuntimeHints.java`; when adding tools or
JSON models that are created or (de)serialized reflectively, keep them under `guru.kumo.operator`. Pass
`-march=compatibility` through `<buildArgs>` in `operator/pom.xml` if the binary must run on other CPUs.

To get a Docker image based on the native executable instead of the jar:

```bash
./scripts/docker-build.sh native   # tags the image matrix-operator, so ./scripts/docker-run.sh works unchanged
```

The native-image build peaks at about 11 GB of RAM, so give the Docker VM at least 12 GB (Colima:
`colima stop && colima start --memory 12`). The native image runs on a Debian (glibc) base because GraalVM only
supports fully static musl executables on x64.

---

## 🔒 Bash Tool Sandbox

Every command of the `Bash` tool runs inside an OS level sandbox (`ShellSandbox`). It enforces:

- **Filesystem**: read-only everywhere except the working directory, temp directories and
  `AGENT_SHELL_SANDBOX_WRITABLE_PATHS`.
- **Hidden paths**: `~/.ssh`, `~/.aws`, `~/.gnupg`, `~/.kube`, ... plus `AGENT_SHELL_SANDBOX_DENY_PATHS` and
  `AGENT_SHELL_SANDBOX_DENY_FILE_NAMES` can be neither read nor written.
- **Environment**: only a small allow-list (`PATH`, `HOME`, ...) plus `AGENT_SHELL_SANDBOX_PASS_ENV` is passed on, so
  API keys of the Matrix process are not visible to commands.
- **Network**: unrestricted, off, or limited to `AGENT_SHELL_SANDBOX_ALLOWED_HOSTS`.

| OS      | Backend                                | Network allow-list                                                                      |
|:--------|:---------------------------------------|:----------------------------------------------------------------------------------------|
| macOS   | `sandbox-exec` (Seatbelt, built in)    | Supported. Only the local filtering proxy port is reachable.                            |
| Linux   | [bubblewrap](https://github.com/containers/bubblewrap) (`bwrap`) | Supported with `socat`. The command runs in an isolated network namespace and reaches the proxy through a Unix socket. |
| Windows | none                                   | Not supported, see below.                                                               |

**macOS** needs nothing to be installed. **Linux** needs `bubblewrap` (and `socat` for `ALLOWED_HOSTS`); inside a
container the sandbox is off by default because the container already is the sandbox. **Windows** has no backend:
with the default `MODE=required` commands are refused. Use WSL2 or Docker (`./scripts/docker-run.sh`), or accept
running unsandboxed with `AGENT_SHELL_SANDBOX_MODE=auto|off`.

`AGENT_SHELL_SANDBOX_MODE` decides what happens when no backend is usable: `required` refuses to run commands,
`auto` runs them unsandboxed with a warning, `off` disables the sandbox.

### How `ALLOWED_HOSTS` works

The OS can only block the network completely, so Matrix starts a small filtering proxy and points `HTTP_PROXY` /
`HTTPS_PROXY` / `ALL_PROXY` of the command at it. The sandbox only lets the command reach that proxy; the proxy
allows a request only if the host matches the list (`example.com` exact, `*.example.com` sub domains). It never
decrypts HTTPS (plain `CONNECT` tunnel). Hosts resolving to loopback or link-local addresses (e.g. cloud metadata) are
always refused; private addresses are only accepted for hosts listed by name. If the proxy cannot be set up (e.g. no
`socat` on Linux) the network is blocked entirely.

### Does `ssh` work inside the sandbox?

Mostly no:

- **With `ALLOWED_HOSTS` set (or `ALLOW_NETWORK=false`)**: blocked. `ssh` does not use HTTP proxies, and the sandbox
  allows no direct connections, so it cannot reach any host. This also applies to `git` over `ssh://` or `git@host:`;
  use `https://` remotes instead.
- **With the network fully open** (`ALLOW_NETWORK=true`, no `ALLOWED_HOSTS`): the connection is possible, but
  `~/.ssh` is hidden and `SSH_AUTH_SOCK` is not passed, so there are no keys, no agent and no `known_hosts`. Only
  password logins or a key placed in the working directory work, and `*.pem` / `id_*` files there are denied by
  default as well.

---

## 🌿 Git Tool

`Git` runs `git` without a shell and without the OS sandbox of `Bash`, but with its own access control. Use it (and
remove `Bash`) when the agent only needs git, for example to commit or push a branch.

- **Commands**: an allow-list (`status`, `diff`, `log`, `commit`, `branch`, `checkout`, `merge`, `rebase`, `fetch`,
  `pull`, `push`, ...). No `clone`, `init`, `config`, `submodule`, interactive modes, or options that run programs or
  write outside the repository (`--upload-pack`, `--exec`, `--output`, ...).
- **Repositories**: only repositories whose work tree is inside the working directory or the other allowed directories.
- **No code execution from repo config**: hooks, fsmonitor, credential helpers, editors and pagers are disabled.
- **Remotes**: `fetch`, `pull`, `push` and `ls-remote` only use a configured remote name (raw URLs are rejected) whose
  resolved URL host is in `AGENT_GIT_ALLOWED_REMOTE_HOSTS`. With the list empty there is no remote access.
- **Push** is off unless `AGENT_GIT_ALLOW_PUSH=true`, and is never forced, never deletes refs, never `--all`/`--mirror`
  and never targets `AGENT_GIT_PROTECTED_BRANCHES` (default `main,master`).
- **Credentials**: your ssh-agent, keychain and `~/.ssh` are not used. Only `AGENT_GIT_SSH_KEY` (+
  `AGENT_GIT_SSH_KNOWN_HOSTS`) for `ssh://` / `git@host:` remotes and `AGENT_GIT_HTTPS_TOKEN` for `https://` remotes
  are, so give them a key or token that can only reach what the agent may push to.

```properties
AGENT_GIT_ALLOWED_REMOTE_HOSTS=bitbucket.rks-cloud.com
AGENT_GIT_ALLOW_PUSH=true
AGENT_GIT_PROTECTED_BRANCHES=main,master,release/*
AGENT_GIT_SSH_KEY=~/.ssh/matrix_deploy_key
AGENT_GIT_SSH_KNOWN_HOSTS=~/.ssh/known_hosts
```

The configuration of the repository itself (remotes, user, diff drivers) is trusted, so only point the tool at
repositories you would run git in yourself.

---

## 🧩 Defining Custom Agents & Skills
Remove "Task" or "Skill" from "BUILT_IN_TOOLS" list if you don't want to trigger Subagents or Skills.

### 🧰 Built-in SubAgent
- bash-agent (tools: Skill,Bash,BashOutput,KillShell,Git)
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
| `Git`                               | Git on local repositories without a shell, with its own remote access control.    |
| `Read` / `Write` / `Edit`           | Precise file inspection and surgical string replacements.                         |
| `Glob` / `Grep`                     | Pure Java file discovery and regular expression searching.                        |
| `TodoWrite`                         | Structured multi-step task planning with status validation.                       |
| `WebFetch`                          | Web content retrieval with HTML-to-Markdown conversion.                           |
| `ImageReader`                       | Multimodal local image and screenshot analysis.                                   |
| `DiscordEditMessage` / `DiscordGetSelfInfo` / `DiscordReplyMessage` / `DiscordSendMessage` | Discord communication. |

---
