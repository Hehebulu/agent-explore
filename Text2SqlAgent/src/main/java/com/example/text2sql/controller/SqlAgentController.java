package com.example.text2sql.controller;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.example.text2sql.streaming.StreamingEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Text2SQL Agent 主控制器
 *
 * 核心接口：
 * - POST /api/text2sql/query — 发起自然语言查询（SSE 流式返回 Graph 执行过程）
 * - GET  /api/text2sql/state?threadId=xxx — 查询指定会话的当前状态
 * - GET  /api/text2sql/history — 获取所有会话列表
 */
@RestController
@RequestMapping("/api/text2sql")
public class SqlAgentController {

    private static final Logger logger = LoggerFactory.getLogger(SqlAgentController.class);

    private final CompiledGraph compiledGraph;
    private final StreamingEventBus streamingEventBus;

    // 内存中维护的活跃会话
    private final Map<String, SessionInfo> activeSessions = new ConcurrentHashMap<>();

    public SqlAgentController(CompiledGraph compiledGraph, StreamingEventBus streamingEventBus) {
        this.compiledGraph = compiledGraph;
        this.streamingEventBus = streamingEventBus;
        logger.info("Text2SQL Agent Controller 初始化完成");
    }

    /**
     * 发起自然语言查询
     *
     * 使用 SSE (Server-Sent Events) 流式返回 Graph 每个节点的执行状态，
     * 在 human_approval 节点自动中断，等待人工审批后需调用 /resume 接口继续。
     */
    @PostMapping(value = "/query", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> query(
            @RequestBody QueryRequest request) throws GraphRunnerException {

        String userQuestion = request.userQuestion();
        String sessionId = request.sessionId() != null
                ? request.sessionId()
                : UUID.randomUUID().toString().substring(0, 8);

        logger.info("收到查询: sessionId={}, question={}", sessionId, userQuestion);

        // 初始化状态
        Map<String, Object> initialState = new HashMap<>();
        initialState.put("user_question", userQuestion);
        initialState.put("session_id", sessionId);
        initialState.put("workflow_status", "INIT");
        initialState.put("chat_history", new ArrayList<>());

        RunnableConfig config = RunnableConfig.builder()
                .threadId(sessionId)
                .build();

        // 记录活跃会话
        activeSessions.put(sessionId, new SessionInfo(sessionId, userQuestion, "INIT"));

        // 注册流式事件 Sink — 用于推送 Node 内部的 token 级输出
        Sinks.Many<Map<String, Object>> streamSink = streamingEventBus.register(sessionId);

        Sinks.Many<ServerSentEvent<Map<String, Object>>> sink =
                Sinks.many().unicast().onBackpressureBuffer();

        Flux<NodeOutput> nodeOutputFlux = compiledGraph.stream(initialState, config);

        nodeOutputFlux.subscribe(
                output -> {
                    try {
                        Map<String, Object> eventData = processNodeOutput(output, sessionId);
                        sink.tryEmitNext(ServerSentEvent.<Map<String, Object>>builder()
                                .data(eventData)
                                .build());
                    } catch (Exception e) {
                        logger.error("处理节点输出失败", e);
                        sink.tryEmitError(e);
                    }
                },
                error -> {
                    logger.error("Graph 执行出错", error);
                    activeSessions.put(sessionId, new SessionInfo(sessionId, userQuestion, "FAILED"));
                    streamingEventBus.unregister(sessionId);
                    sink.tryEmitError(error);
                },
                () -> {
                    logger.info("Graph 执行完成: sessionId={}", sessionId);
                    activeSessions.put(sessionId, new SessionInfo(sessionId, userQuestion, "COMPLETED"));
                    streamingEventBus.unregister(sessionId);
                    sink.tryEmitComplete();
                }
        );

        // 合并 Graph 节点输出和流式 token 输出
        Flux<ServerSentEvent<Map<String, Object>>> streamEvents = streamSink.asFlux()
                .map(eventData -> ServerSentEvent.<Map<String, Object>>builder()
                        .data(eventData)
                        .build());

        return Flux.merge(sink.asFlux(), streamEvents)
                .doOnCancel(() -> {
                    logger.info("客户端断开连接: sessionId={}", sessionId);
                    streamingEventBus.unregister(sessionId);
                })
                .doOnError(e -> logger.error("SSE 流异常: sessionId={}", sessionId, e));
    }

    /**
     * 获取指定会话的当前状态
     */
    @GetMapping("/state")
    public Map<String, Object> getState(@RequestParam String threadId) {
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        StateSnapshot snapshot = compiledGraph.getState(config);

        if (snapshot == null) {
            return Map.of("error", "未找到会话状态: " + threadId);
        }

        return Map.of(
                "threadId", threadId,
                "state", snapshot.state().data(),
                "nextNode", snapshot.next()
        );
    }

    /**
     * 获取所有活跃会话
     */
    @GetMapping("/sessions")
    public Map<String, SessionInfo> getSessions() {
        return new HashMap<>(activeSessions);
    }

    private Map<String, Object> processNodeOutput(NodeOutput output, String sessionId) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("node", output.node());

        // 区分中断事件和正常事件
        if (output instanceof InterruptionMetadata) {
            InterruptionMetadata metadata = (InterruptionMetadata) output;
            event.put("type", "interruption");
            event.put("message", "工作流已暂停，等待人工审批 SQL");

            Map<String, Object> meta = new LinkedHashMap<>();
            metadata.metadata().ifPresent(meta::putAll);
            event.put("metadata", meta);

            activeSessions.put(sessionId, new SessionInfo(sessionId,
                    (String) meta.getOrDefault("user_question", ""),
                    "WAITING_APPROVAL"));

            logger.info("=== 中断事件 === sessionId={}, node={}, metadata={}",
                    sessionId, output.node(), meta.keySet());
        } else {
            event.put("type", "node_output");
            event.put("state", output.state().data());
        }

        return event;
    }

    /**
     * 查询请求
     */
    public record QueryRequest(
            String userQuestion,
            String sessionId
    ) {}

    /**
     * 会话信息
     */
    public record SessionInfo(
            String sessionId,
            String lastQuestion,
            String status
    ) {}
}
