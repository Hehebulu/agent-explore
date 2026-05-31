# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Monorepo Structure

This project lives under a parent POM at `agent-explore/pom.xml` which manages shared versions:

| Property | Value |
|----------|-------|
| Java | 17 |
| Spring Boot | 3.5.7 |
| Spring AI | 1.1.0 |
| Spring AI Alibaba | 1.1.0.0 |

The parent POM imports BOMs for `spring-ai-bom`, `spring-ai-alibaba-bom`, and `spring-ai-alibaba-extensions-bom`. All module dependencies inherit managed versions from these BOMs.

## Build & Run

```bash
# Build from monorepo root (D:\workspace\A_AI_CODE\agent-explore)
mvn compile -pl customer-agent

# Run the app
mvn spring-boot:run -pl customer-agent

# Package
mvn package -pl customer-agent -DskipTests
```

## Infrastructure Dependencies

The app requires **PostgreSQL** (with `pgvector` extension) and **Redis** running locally on default ports:

- PostgreSQL: `localhost:5432`, database `customer_agent`, user/pass `postgres/postgres`
- Redis: `localhost:6379`, no password

On startup, `schema.sql` auto-creates tables (`spring.sql.init.mode=always`). `data.sql` seeds FAQ and sensitive word data.

PGVector is used for FAQ semantic search (HNSW index, cosine distance, 1536 dimensions matching `text-embedding-3-small`).

## Architecture Overview

This is an **intelligent customer service system** built on Spring AI Alibaba Graph — a LangGraph-style state machine where each processing step is a node, and conditional edges route between them based on LLM analysis results.

### Graph Workflow Topology

```
START → security_check → emotion_analysis → faq_search → intent_recognition → [route by confidence]
                                                                                    ├─ HIGH (≥0.8)   → biz_dispatch → END
                                                                                    ├─ MEDIUM (0.4-0.8) → clarify → loop back to intent_recognition
                                                                                    ├─ LOW (<0.4)    → llm_chat → END
                                                                                    └─ transfer_human/complaint → human_takeover → END
```

Alternative exits:
- `security_check` blocks → immediate END (via EmotionDispatcher checking `security_blocked`)
- `emotion_analysis` escalation (`need_human=true`) → `human_takeover` → END
- `faq_search` high-confidence match → direct END with FAQ answer

### Component Layers

| Layer | Package | Role |
|-------|---------|------|
| Graph nodes | `graph.node.*` | Each node is an `AsyncNodeActionWithConfig` that reads/writes state |
| Graph dispatchers | `graph.dispatcher.*` | `EdgeAction` implementations that decide the next node based on state |
| Graph config | `graph.GraphConfig` | Wire up nodes + edges, register `MemorySaver` for checkpointing |
| Services | `service.*` | Business logic called by nodes (LLM prompts, DB lookups, vector search) |
| Controllers | `controller.*` | REST API + SSE streaming endpoints |
| Repositories | `repository.*` | MyBatis-Plus mappers extending `BaseMapper<T>` |
| Models | `model.*` | JPA entities (`@TableName`) + DTOs |

### Key Design Decisions

- **SSE streaming with reactive backpressure**: `CustomerAgentService.processMessage()` uses `Sinks.Many` + `Flux<NodeOutput>` — the Graph executes node-by-node and each node's output is converted to an SSE event delivered to the browser. The frontend uses `EventSource`-style `fetch` with `ReadableStream` reader.
- **State machine with checkpointing**: `MemorySaver` checkpoints graph state after each node. `sessionId` is used as `threadId` in `RunnableConfig`, so each conversation session has isolated state.
- **Dual-mode conversation**: Normal mode runs through the AI Graph; human takeover mode (`human_serving` status) bypasses the Graph entirely — messages are stored directly and delivered via polling.
- **FAQ short-circuit**: If vector search (PGVector cosine similarity ≥ 0.9) matches a known Q&A pair, the answer is returned immediately without invoking the LLM chain.
- **Two-phase emotion detection**: Keyword matching first (fast path for escalation keywords like "投诉"), LLM-based analysis as fallback.
- **Intent confidence routing**: HIGH → business dispatch (LLM generates domain answer), MEDIUM → clarification loop (max 3 rounds, then escalate to human), LOW → free-form LLM chat.
- **Session memory window**: Last 10 messages injected into intent recognition prompt for conversational context.

### Frontend

Two Thymeleaf-free static HTML files served from `src/main/resources/static/`:

- **`index.html`** — Customer-facing chat UI. SSE streaming via `fetch` + `ReadableStream`, human polling mode with `setInterval`, quick action buttons.
- **`agent.html`** — Agent console (left queue panel + right chat area). Polls `/api/agent/queue` every 3s, polles messages every 2s via `sinceId` incremental fetch.

### External API Configuration

Uses Spring AI OpenAI-compatible interface (configurable via env vars):

| Variable | Default |
|----------|---------|
| `AI_API_KEY` | `sk-xxx` |
| `AI_BASE_URL` | `https://api.openai.com` |
| `AI_CHAT_MODEL` | `gpt-4o` |
| `AI_EMBEDDING_MODEL` | `text-embedding-3-small` |

### Database Schema (5 tables)

- `customer_session` — session lifecycle (active/human_waiting/human_serving/closed/resolved)
- `customer_message` — chat history with emotion/intent/FAQ metadata
- `faq_knowledge` — FAQ Q&A pairs (text storage; vectors managed externally via PGVector)
- `sensitive_word` — content moderation word list
- `human_queue` — agent handoff queue with priority support
