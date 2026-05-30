# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Build
mvn clean compile

# Run (starts on port 8080)
mvn spring-boot:run

# Package as JAR
mvn clean package -DskipTests
```

## Architecture

This is a **Text2SQL Agent** — a Spring Boot 3.5.7 (Java 17) application that converts natural language to SQL with a mandatory human approval step (Human-in-the-Loop).

### Tech Stack
- **Framework**: Spring Boot 3.5.7 + Spring AI 1.1.0 + Spring AI Alibaba Graph 1.1.0.0
- **LLM**: Alibaba DashScope (qwen-max model)
- **Database**: H2 in-memory (MySQL compatibility mode) for dev; MySQL Connector for production
- **Frontend**: Single static HTML page (`src/main/resources/static/index.html`) with vanilla JS + SSE streaming
- **Build**: Maven with `spring-boot-maven-plugin`

### Core Workflow (StateGraph)

```
START → user_question → list_tables → get_schema → generate_sql
  → check_sql → human_approval → execute_sql → summarize → END
```

The `check_sql` node is now correctly wired into the graph (was previously documented but not actually registered).

Each node is a class in `node/` implementing `AsyncNodeActionWithConfig`. The graph is defined in `config/GraphConfig.java`.

**Conditional routing:**
- `check_sql` → always routes to `human_approval` (via `SqlCheckDispatcher`)
- `human_approval` → routes to `execute_sql` (APPROVE/MODIFY), `generate_sql` (REJECT), or `END` (via `ApprovalDispatcher`)

### Key Packages

| Package | Role |
|---------|------|
| `node/` | Graph workflow nodes — each processes one step |
| `tool/` | `@Tool`-annotated classes with actual logic (LLM calls, JDBC) |
| `config/` | Spring beans: `GraphConfig` (workflow), `AgentConfig` (ChatClient), `SecurityConfig` (SQL validation rules) |
| `controller/` | REST + SSE: `SqlAgentController` (main query API), `HumanApprovalController` (approval resume API) |
| `security/` | `SqlSecurityValidator` — multi-layer SQL safety check (keyword blacklist, JSqlParser AST, regex patterns) |
| `dispatcher/` | Conditional edge routing for the graph |
| `streaming/` | `StreamingEventBus` — bridges node-internal token streams to SSE |
| `agent/` | `SqlAgentPrompt` — all LLM prompt templates (system, review, summarize) |

### Human-in-the-Loop Mechanism

1. `HumanApprovalNode` implements `InterruptableAction` — its `interrupt()` method pauses the graph and saves a checkpoint
2. Frontend detects the `interruption` SSE event and shows the approval panel
3. User clicks APPROVE/REJECT/MODIFY → POST to `/api/text2sql/approval/{action}`
4. `HumanApprovalController.resume()` builds a `RunnableConfig` with `HUMAN_FEEDBACK_METADATA_KEY` and `STATE_UPDATE_METADATA_KEY`, then calls `compiledGraph.stream(null, resumeConfig)`
5. Graph restores from checkpoint, `interrupt()` detects new feedback and returns empty to continue, `apply()` processes the decision

### State Fields

Key state fields flowing through the graph: `user_question`, `available_tables`, `table_schemas`, `generated_sql`, `sql_valid`, `sql_check_result`, `human_action`, `executed_sql`, `query_results`, `summary`, `workflow_status`, `next_node`.

State merge strategy: `REPLACE` (defined via `KeyStrategyFactory` in `GraphConfig`).

### SSE Streaming Pattern

- `SqlAgentController.query()` returns `Flux<ServerSentEvent>` — graph node outputs are emitted as SSE events
- `StreamingEventBus` is a per-session `Sinks.Many` bridge that `GenerateSqlNode` and `SummarizeNode` write token-level output into, while the controller reads and merges it into the SSE stream
- Frontend reads the SSE stream via `fetch().body.getReader()` and handles events: `node_output`, `stream`, `stream_start`, `stream_end`, `interruption`
