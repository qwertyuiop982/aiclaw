# aiclaw - AI Agent command-line tool

`aiclaw` is a lightweight AI Agent CLI written in Java.
A simple YAML config defines an "Agent App" (system prompt + tool set + model) so you can
chat with a large model in your terminal and have it call local tools on demand
(read/write files, run commands, search the web, parse links, attach images).

> Analogy: `aiclaw` is like giving an LLM a toolbox it can invoke in the terminal. You describe
> the task in natural language; the model decides which tools to call.

## ✨ Features

- 🤖 **Agent framework** - system prompt + conversation history + tool-call loop (ReAct style)
- 🔌 **OpenAI-compatible API** - works with OpenAI / DeepSeek / Qwen / Moonshot / self-hosted vLLM,
  or any service that exposes Chat Completions
- 🧰 **14 built-in tools**:
  - Files: `read_file` / `write_file` / `read_file_lines` / `write_file_lines` / `list_dir` /
    `move_file` (move|copy) / `delete_file`
  - Network: `bing_search` (URLs only) / `fetch_url` (text + image list) / `http_get`
  - System: `shell` / `terminal` / `echo`
  - `terminal` is a persistent independent local bash session, not a security sandbox; reuse its `terminal_id` to keep cwd and environment.
- 🖼 **Multimodal upload** - in the REPL, type `@/abs/path/image.png` to attach an image as a
  user message (OpenAI `image_url` data URI)
- 🧠 **Reasoning parameters** - free-form pass-through, e.g.:
  - `--thinking-arg reasoning_effort=high` (OpenAI o-series / Gemini 2.5)
  - `--thinking-arg thinking.type=enabled --thinking-arg thinking.budget_tokens=1000`
    (Claude / Gemini thinking)
  - `--thinking-arg thinking.type=enabled` (DeepSeek / Qwen thinking)
- 🛡 **Three layers of tool gating**:
  1. Per-agent YAML whitelist
  2. CLI blacklist via `--no-<tool>`
  3. Runtime REPL toggle via `/tool enable|disable <name>`
- 📜 **Agent App config** - define multiple agents via YAML; contexts stay isolated
- 💬 **Two modes** - interactive REPL plus one-shot `ask`
- 🔐 **Layered configuration** - environment variables > `~/.aiclaw/config.properties` >
  `./aiclaw.properties` > built-in defaults
- 📦 **Zero-dependency distribution** - single fat JAR

## 🚀 Quick start

### 1. Global install (recommended)

Once installed, `aiclaw` is available from any directory:

```bash
./gradlew installDist
mkdir -p ~/.local/bin
ln -sf "$(pwd)/build/install/aiclaw/bin/aiclaw" ~/.local/bin/aiclaw
grep -q 'HOME/.local/bin' ~/.bashrc || echo 'export PATH=$HOME/.local/bin:$PATH' >> ~/.bashrc
source ~/.bashrc
```

> 💡 Use `ln -sf` (a symlink) instead of `cp`; rebuilding the project automatically picks up the
> new version with no further work.
> ⚠️ If `cp` reports "are the same file", the symlink is already in place and the step can be
> skipped.

### 2. Configure your API key

**Option A - environment variables**

```bash
export AICLAW_API_KEY="<your full provider key; sk- prefix is not required>"
export AICLAW_BASE_URL="https://api.openai.com/v1"
# export AICLAW_MODEL="<model-id>"  # optional; in the REPL press Ctrl+F to choose from /models
```

**Option B - configuration file (recommended)**

```bash
mkdir -p ~/.aiclaw
cat > ~/.aiclaw/config.properties <<EOF
api.key=<your full provider key; sk- prefix is not required>
api.base.url=https://api.openai.com/v1
# api.model=<model-id>  # optional; in the REPL press Ctrl+F to choose from /models
EOF
```

Precedence: `environment variable > --api-key/--base-url/--model CLI > ~/.aiclaw/config.properties >
./aiclaw.properties > built-in defaults`.

The project no longer ships a hard-coded model name. In the TTY REPL, `Ctrl+F` requests `/models`
with the currently effective `base URL + API key`, then lets you pick one with Up/Down and Enter
(session-only). To persist, run `aiclaw profile set-model <profile> <model-id>`.

### 3. Try it out

```bash
aiclaw                            # quick start if unconfigured, otherwise default agent REPL
aiclaw list                       # list all built-in agents
aiclaw show coder                 # show an agent's configuration

aiclaw coder                      # start the coder agent's REPL (most common)
aiclaw researcher                 # start the researcher agent's REPL
aiclaw coder "write a Hello World" # one-shot ask

aiclaw --help                     # full help
aiclaw help                       # same
aiclaw tools                      # list all 12 built-in tools

# Pick a model from the endpoint and enable thinking
aiclaw --base-url https://api.deepseek.com/v1 \
        --model your-model-id \
        --thinking-arg thinking.type=enabled \
        ask coder "hello"

# Disable shell + delete_file at startup (paranoid mode)
java -jar build/libs/aiclaw-1.0.0.jar --no-shell --no-delete_file coder
```

## 📖 Command reference

```
aiclaw [command] [options]

Commands:
  list                          list every available agent
  show <agent>                  show an agent's configuration and system prompt
  tools                         list built-in tools
  run <agent>                   start an interactive session with the agent (default)
  ask <agent> <message>         one-shot ask, no REPL
  <agent>                       alias for run (shorthand)

Options:
  --base-url <url>              LLM API base URL (default https://api.openai.com/v1)
  --api-key <key>               API key (overrides configuration)
  --model <name>                override the agent yaml's model
  --agents-dir <dir>            directory containing agent YAMLs
  --thinking-arg k=v            pass through a thinking parameter (repeatable)
                                top-level keys land at the request root
                                  reasoning_effort=high
                                nested keys go through extra_body
                                  thinking.budget_tokens=1000
  --no-<tool>                   blacklist a tool (repeatable, e.g. --no-shell)
  -h, --help                    show help
  -v, --version                 show version

REPL built-in directives:
  /help                         show REPL help
  /exit  /quit                  exit
  /reset                        clear conversation history (keeps the system prompt)
  Ctrl+A                        toggle thinking via the profile's `thinking.N.*` (TTY, session-only)
  Ctrl+Y                        new in-memory conversation (clear history, keep current settings)
  Ctrl+F                        pick a model from the current endpoint's /models (TTY, session-only)
  /tools                        list tools enabled for the current agent
  /tool                         list every tool with its switch state
  /tool enable <name>           enable a tool at runtime
  /tool disable <name>          disable a tool at runtime
  /thinking                     show the current thinking parameters
  /thinking k=v [k=v ...]       set thinking parameters at runtime
  /history                      show the message count
  @/abs/path.png                attach an image (multimodal, injected as a user message)
```

## 🧠 Thinking parameters and Ctrl+A

The thinking modes map to the following request fields; the program never branches on model name:

| Mode | Request field                                | Off-action        |
|------|----------------------------------------------|-------------------|
| `1`  | `reasoning.effort`                           | sends `none`      |
| `2`  | `thinking.type`                              | sends `disabled`  |
| `3`  | `generationConfig.thinkingConfig.thinkingBudget` | removes the field |
| `4`  | `thinking.type=enabled`                      | removes the field |
| `5`  | `reasoning.mode=pro`                         | removes the field |

Combination constraints: `1` and `3` are mutually exclusive, `2` and `4` are mutually exclusive,
`5` cannot stand alone.

`Ctrl+A` reads only the user-defined `thinking.N.*` fields in the current profile, for example:

```properties
thinking.modes=1,2,5

thinking.1.value=high
thinking.1.options=none,low,medium,high
thinking.1.off=none

thinking.2.value=enabled
thinking.2.options=enabled,adaptive,disabled
thinking.2.off=disabled

thinking.5.off=remove
```

Budget mode example:

```properties
thinking.modes=3,4
thinking.3.value=32768
thinking.3.options=1024,8192,32768
thinking.3.off=remove
thinking.4.off=remove
```

`off=remove` removes the field from the request; `off=0`, `off=none`, `off=disabled` or `off=null`
send the literal value. If a particular endpoint explicitly accepts budget `0`, configure
`thinking.3.off=0` directly - no model-name branching is needed.

- Mode `2` only: `Ctrl+A` cycles through `thinking.2.options`; when no options exist it toggles
  between `enabled` and `disabled`.
- Mode `4` or `5` only: `Ctrl+A` adds or removes the field.
- Modes `1` and `3`: `Ctrl+A` opens an Up/Down/Enter picker over the corresponding
  `thinking.N.options`.
- Multiple modes: `Ctrl+A` first asks which control to operate on.

The thinking shortcuts only affect the current REPL session; they never write back to the
profile.

## 🧠 Agent App configuration (YAML)

```yaml
name: my-agent
description: A one-line description
# model: your-model-id  # optional; defaults to the active config or Ctrl+F in the REPL
system_prompt: |
  Your role and instructions ...
greeting: Hi, I am ...
tools:
  - read_file
  - read_file_lines
  - bing_search
  - fetch_url
options:
  temperature: 0.2
```

The repository ships three example agents:

- `coder` - coding assistant (files + shell)
- `researcher` - web research (bing_search + fetch_url)
- `shell-helper` - natural-language shell runner

## 🧰 Built-in tools

| Name                  | Description                                                  |
|-----------------------|--------------------------------------------------------------|
| `read_file`           | Read a text file (<= 8000 chars)                            |
| `write_file`          | Write a text file                                            |
| `read_file_lines`     | Read by line-number range (read/head/tail)                   |
| `write_file_lines`    | Write by line number (overwrite/append/insert)               |
| `list_dir`            | List a directory                                             |
| `move_file`           | Move or copy (action=move|copy)                              |
| `delete_file`         | Delete a file or directory (with safety guards)              |
| `shell`               | Run a bash command                                           |
| `bing_search`         | Bing search - **returns URL list only**                      |
| `fetch_url`           | Fetch a URL -> plain text + image URL list (OCR is a stub)   |
| `http_get`            | Simple HTTP GET                                              |
| `echo`                | Echo text back                                               |

Adding a tool: drop a class implementing `Tool` under `tools/`, then register it in
`ToolRegistry.defaults()`.

## 🏗️ Architecture

```
Main
 └── Cli  (command parsing / REPL / @-multimodal / /tool runtime toggle)
      └── Agent  (system prompt + history + tool-call loop + askWithImage)
           ├── LlmClient
           │    └── OpenAiClient  (passes ThinkingConfig through: reasoning_effort / extra_body.thinking)
           └── :tools module (Tool API + registry + built-in tool implementations)
                ├── EchoTool
                ├── ReadFileTool / WriteFileTool
                ├── ReadFileLinesTool / WriteFileLinesTool
                ├── MoveFileTool / DeleteFileTool
                ├── ShellTool
                ├── BingSearchTool / FetchUrlTool / HttpGetTool
                ├── ToolArgs / PathPolicy / UrlPolicy
                └── HtmlTextExtractor (Jsoup + OcrBridge stub)
```

## 🧪 Development and testing

```bash
./gradlew test          # run JUnit tests
./gradlew build         # compile + test + assemble the main fat jar (auto-builds tools first)
./gradlew :tools:jar    # build only tools/build/libs/tools-1.0.0.jar
# do NOT call :jar directly; call `build` or `:tools:jar` instead
./gradlew clean         # clean
```

## 📦 Standard Linux installation

The published release contains a self-contained fat JAR and an installer for standard Linux.
Java 17+ and `curl` are required. The installer downloads the selected release from GitHub,
installs the launcher under `~/.local/bin`, stores the JAR under `~/.local/lib/aiclaw`, and adds
`AICLAW_HOME` and `~/.local/bin` to `~/.profile`.

```bash
curl -fsSL https://github.com/qwertyuiop982/aiclaw/releases/latest/download/install.sh | bash
# or download first and inspect it:
curl -fsSLO https://github.com/qwertyuiop982/aiclaw/releases/latest/download/install.sh
bash install.sh
```

Use `AICLAW_VERSION=v1.0.0` to install a specific release, `AICLAW_PREFIX=/opt/aiclaw` to change
the installation prefix, or `AICLAW_REPO=owner/repo` for a fork. Start a new shell or run
`export PATH="$HOME/.local/bin:$PATH"` after installation.

## 📜 License

MIT# aiclaw
