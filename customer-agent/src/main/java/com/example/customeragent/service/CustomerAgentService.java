package com.example.customeragent.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.example.customeragent.model.EmotionType;
import com.example.customeragent.model.IntentType;
import com.example.customeragent.model.Message;
import com.example.customeragent.model.Session;
import com.example.customeragent.repository.MessageRepository;
import com.example.customeragent.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 客服 Graph 工作流编排服务
 *
 * 核心职责：
 * 1. 管理会话生命周期（首次访问自动创建 Session）
 * 2. 驱动 Spring AI Alibaba Graph 状态图执行
 * 3. 将 Graph 各节点的输出转换为 SSE 事件流
 * 4. 持久化 AI 回复消息 + 更新会话状态
 *
 * 工作流路径：
 * START → security_check → emotion_analysis → faq_search → intent_recognition → biz_dispatch/llm_chat/human_takeover → END
 */
@Service
public class CustomerAgentService {

    private static final Logger logger = LoggerFactory.getLogger(CustomerAgentService.class);

    private final CompiledGraph compiledGraph;
    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;

    public CustomerAgentService(CompiledGraph compiledCustomerAgentGraph,
                                 SessionRepository sessionRepository,
                                 MessageRepository messageRepository) {
        this.compiledGraph = compiledCustomerAgentGraph;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    /**
     * 处理用户消息（核心入口）
     *
     * @param sessionId 会话ID
     * @param userId    用户ID
     * @param content   用户消息内容
     * @param channel   渠道来源（web/wechat/app）
     * @return SSE 事件流，前端通过 EventSource/Reader 逐条消费
     */
    public Flux<Map<String, Object>> processMessage(String sessionId, String userId,
                                                     String content, String channel) {
        // 1. 保证 Session 存在（首次访问自动创建）
        Session session = sessionRepository.selectById(sessionId);
        if (session == null) {
            session = Session.builder()
                    .id(sessionId)
                    .userId(userId)
                    .channel(channel)
                    .status("active")
                    .negativeRounds(0)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            sessionRepository.insert(session);
        }

        // 2. 存储用户消息
        Message userMsg = Message.builder()
                .sessionId(sessionId)
                .role("user")
                .content(content)
                .nodeName("user_input")
                .createdAt(LocalDateTime.now())
                .build();
        messageRepository.insert(userMsg);

        // 3. 构建 Graph 初始状态
        Map<String, Object> initialState = new HashMap<>();
        initialState.put("session_id", sessionId);
        initialState.put("user_id", userId);
        initialState.put("channel", channel);
        initialState.put("user_message", content);
        initialState.put("clarify_count", 0);
        initialState.put("need_clarify", false);

        // 4. 以 sessionId 作为 threadId 保证状态隔离
        RunnableConfig config = RunnableConfig.builder().threadId(sessionId).build();
        Sinks.Many<Map<String, Object>> sink = Sinks.many().unicast().onBackpressureBuffer();

        try {
            Flux<NodeOutput> nodeOutputFlux = compiledGraph.stream(initialState, config);

            // 5. 订阅 Graph 输出流，逐节点转换为 SSE 事件
            nodeOutputFlux.subscribe(
                    output -> {
                        try {
                            if (StateGraph.END.equals(output.node()) || "__END__".equals(output.node())) {
                                return; // 忽略结束标记
                            }
                            Map<String, Object> event = processNodeOutput(output, sessionId);
                            if (!event.isEmpty()) {
                                sink.tryEmitNext(event);
                            }
                        } catch (Exception e) {
                            logger.error("处理节点输出失败", e);
                        }
                    },
                    error -> {
                        logger.error("Graph执行失败", error);
                        Map<String, Object> errorEvent = new HashMap<>();
                        errorEvent.put("type", "error");
                        errorEvent.put("message", error.getMessage());
                        sink.tryEmitNext(errorEvent);
                        sink.tryEmitComplete();
                    },
                    () -> {
                        logger.info("Graph执行完成: sessionId={}", sessionId);
                        Map<String, Object> doneEvent = new HashMap<>();
                        doneEvent.put("type", "done");
                        doneEvent.put("sessionId", sessionId);
                        sink.tryEmitNext(doneEvent);
                        sink.tryEmitComplete();
                    }
            );
        } catch (Exception e) {
            logger.error("Graph启动失败", e);
            Map<String, Object> errorEvent = new HashMap<>();
            errorEvent.put("type", "error");
            errorEvent.put("message", e.getMessage());
            sink.tryEmitNext(errorEvent);
            sink.tryEmitComplete();
        }

        return sink.asFlux();
    }

    /**
     * 将单个节点的输出转换为前端事件 Map
     *
     * 处理逻辑：
     * - 有 ai_response → 封装为助手消息事件，持久化入库，更新会话状态
     * - need_clarify=true → 标记为澄清事件，前端显示追问
     * - human_takeover 节点 → 标记为转接事件，前端切换轮询模式
     */
    private Map<String, Object> processNodeOutput(NodeOutput output, String sessionId) {
        Map<String, Object> event = new HashMap<>();
        String nodeName = output.node();
        event.put("type", "node_output");
        event.put("node", nodeName);

        Map<String, Object> state = output.state().data();

        // 有 AI 回复 → 持久化 + 更新会话
        if (state.containsKey("ai_response")) {
            String aiResponse = state.get("ai_response").toString();
            event.put("content", aiResponse);
            event.put("role", "assistant");

            Message aiMsg = Message.builder()
                    .sessionId(sessionId)
                    .role("assistant")
                    .content(aiResponse)
                    .nodeName(nodeName)
                    .createdAt(LocalDateTime.now())
                    .build();

            // 附加元数据（情绪、意图、置信度、FAQ命中）
            if (state.containsKey("emotion_type")) {
                try {
                    aiMsg.setEmotionType(EmotionType.valueOf(state.get("emotion_type").toString().toUpperCase()));
                } catch (Exception ignored) {}
            }
            if (state.containsKey("emotion_score") && state.get("emotion_score") instanceof BigDecimal score) {
                aiMsg.setEmotionScore(score);
            }
            if (state.containsKey("intent")) {
                try {
                    aiMsg.setIntentType(IntentType.valueOf(state.get("intent").toString().toUpperCase()));
                } catch (Exception ignored) {}
            }
            if (state.containsKey("confidence") && state.get("confidence") instanceof Number num) {
                aiMsg.setConfidence(BigDecimal.valueOf(num.doubleValue()));
            }
            if (state.containsKey("faq_matched")) {
                aiMsg.setFaqHit(Boolean.TRUE.equals(state.get("faq_matched")));
            }

            messageRepository.insert(aiMsg);

            // 同步更新 Session 的当前意图和情绪状态
            Session s = sessionRepository.selectById(sessionId);
            if (s != null) {
                if (state.containsKey("intent")) {
                    try {
                        s.setCurrentIntent(IntentType.valueOf(state.get("intent").toString().toUpperCase()));
                    } catch (Exception ignored) {}
                }
                if (state.containsKey("emotion_type")) {
                    try {
                        s.setEmotionState(EmotionType.valueOf(state.get("emotion_type").toString().toUpperCase()));
                    } catch (Exception ignored) {}
                }
                if (state.containsKey("need_human") && Boolean.TRUE.equals(state.get("need_human"))) {
                    s.setNegativeRounds(s.getNegativeRounds() + 1);
                }
                sessionRepository.updateById(s);
            }
        }

        if (state.containsKey("need_clarify") && Boolean.TRUE.equals(state.get("need_clarify"))) {
            event.put("type", "clarify");
        }
        if ("human_takeover".equals(nodeName)) {
            event.put("type", "transfer");
            event.put("queueNumber", state.getOrDefault("human_queue_number", 0));
            event.put("waitTime", state.getOrDefault("human_wait_time", 0));
        }

        return event;
    }
}
