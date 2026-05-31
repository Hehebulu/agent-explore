# 智能客服系统 MVP 设计文档

## 项目目标

构建「AI客服 + 大模型 + 人工客服」协同工作平台 MVP。

核心链路：用户消息 → 敏感词检测 → 情绪识别 → FAQ检索 → AI意图识别 → 置信度路由 → 业务分发/LLM对话/人工兜底

技术栈：Spring Boot 3.5.7 + Spring AI 1.1.0 + Spring AI Alibaba Graph Core 1.1.0.0 + PostgreSQL/PGVector + Redis + RabbitMQ

## 架构方案

采用 Spring AI Alibaba Graph 工作流模式，将客服流程建模为 Node + Dispatcher 的 Pipeline：

```
UserMessageNode → SecurityCheckNode → EmotionNode → FaqNode → IntentRecognitionNode
                                                                       ↓
                                                         ConfidenceDispatcher
                                                    /          |           \
                                                   /           |            \
                                            HighNode(0.8+)  MediumNode(0.4-0.8)  LowNode(<0.4)
                                                 ↓                ↓                    ↓
                                           BizDispatchNode   ClarifyNode    LlmChatNode/HumanTakeoverNode
```

人工接管触发（EmotionDispatcher）：
- emotionScore >= 0.8
- 含投诉/起诉/退钱/欺诈/骗子/举报等关键词
- 连续3轮负面情绪
- 用户主动要求转人工

## 项目结构（DDD 分层）

```
customer-agent/src/main/java/com/example/customeragent/
├── CustomerAgentApplication.java
├── domain/
│   ├── model/
│   │   ├── Message.java
│   │   ├── Session.java
│   │   ├── EmotionType.java
│   │   ├── IntentType.java
│   │   └── ConfidenceLevel.java
│   ├── service/
│   │   ├── SecurityCheckService.java
│   │   ├── EmotionAnalysisService.java
│   │   ├── FaqService.java
│   │   ├── IntentRecognitionService.java
│   │   └── SessionMemoryService.java
│   └── repository/
│       ├── MessageRepository.java
│       └── SessionRepository.java
├── application/
│   ├── dto/
│   ├── service/
│   │   └── CustomerAgentService.java
│   └── graph/
│       ├── CustomerAgentGraph.java
│       ├── node/  (SecurityCheck/Emotion/Faq/IntentRecognition/BizDispatch/Clarify/LlmChat/HumanTakeover)
│       └── dispatcher/  (ConfidenceDispatcher/EmotionDispatcher)
├── infrastructure/
│   ├── config/  (LlmConfig/RedisConfig/VectorStoreConfig/GraphConfig)
│   ├── repository/impl/
│   ├── llm/  (ChatClientProvider)
│   └── vector/  (FaqVectorStore)
└── interfaces/
    └── rest/  (ChatController SSE流式/SessionController)
```

## 数据库设计

- **session**: 会话表（id/user_id/channel/status/emotion_state/current_intent/timestamps）
- **message**: 消息表（id/session_id/role/content/emotion_type/emotion_score/intent/confidence/faq_hit/node_name/timestamp）
- **faq_knowledge**: FAQ知识库（id/question/answer/category/embedding(vector 1536)/enabled）
- **sensitive_word**: 敏感词表（id/word/category/enabled）

## API 设计

- POST `/api/chat/send` — 发送消息（SSE流式）
- GET `/api/session/{id}` — 会话详情
- GET `/api/session/{id}/messages` — 历史消息
- POST `/api/session` — 创建会话
- POST `/api/session/{id}/transfer-human` — 主动转人工
- GET `/api/human/queue/status` — 排队状态
- POST `/api/human/leave-message` — 离线留言

SSE事件类型: thinking/message/faq_hit/clarify/transfer/error/done

## MVP 开发计划

1. 项目骨架、数据库、Redis/VectorStore 配置、领域模型
2. Graph 工作流各 Node + Dispatcher + SSE 流式
3. 业务集成（LLM敏感词/情绪/意图、FAQ向量检索、人工接管）
4. 前端页面 + 端到端测试
