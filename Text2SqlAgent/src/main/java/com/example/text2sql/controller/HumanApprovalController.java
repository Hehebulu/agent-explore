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

/**
 * Human-in-the-Loop 审批控制器
 *
 * 核心接口：
 * - POST /api/text2sql/approval/approve — 批准 SQL 执行
 * - POST /api/text2sql/approval/reject  — 拒绝 SQL 执行
 * - POST /api/text2sql/approval/modify  — 修改 SQL 后执行
 * - POST /api/text2sql/approval/resume  — 通用 resume 接口
 * - GET  /api/text2sql/approval/pending — 获取等待审批的任务列表
 */
@RestController
@RequestMapping("/api/text2sql/approval")
public class HumanApprovalController {

    private static final Logger logger = LoggerFactory.getLogger(HumanApprovalController.class);

    private final CompiledGraph compiledGraph;
    private final StreamingEventBus streamingEventBus;

    // 存储等待审批的任务信息
    private final Map<String, ApprovalTask> pendingTasks = new LinkedHashMap<>();

    public HumanApprovalController(CompiledGraph compiledGraph, StreamingEventBus streamingEventBus) {
        this.compiledGraph = compiledGraph;
        this.streamingEventBus = streamingEventBus;
        logger.info("HumanApprovalController 初始化完成");
    }

    /**
     * 批准 SQL 执行
     */
    @PostMapping(value = "/approve", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> approve(
            @RequestBody ApprovalRequest request) throws GraphRunnerException {

        logger.info("人工审批通过: sessionId={}", request.sessionId());
        return resume(request.sessionId(), "APPROVE", request.comment(), null);
    }

    /**
     * 拒绝 SQL 执行
     */
    @PostMapping(value = "/reject", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> reject(
            @RequestBody ApprovalRequest request) throws GraphRunnerException {

        logger.info("人工审批拒绝: sessionId={}, reason={}", request.sessionId(), request.comment());
        return resume(request.sessionId(), "REJECT", request.comment(), null);
    }

    /**
     * 修改 SQL 后执行
     */
    @PostMapping(value = "/modify", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> modify(
            @RequestBody ModifyRequest request) throws GraphRunnerException {

        logger.info("人工修改SQL: sessionId={}, modifiedSql={}", request.sessionId(), request.modifiedSql());
        return resume(request.sessionId(), "MODIFY", request.comment(), request.modifiedSql());
    }

    /**
     * 通用 resume 方法
     */
    @PostMapping(value = "/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> resume(
            @RequestBody ResumeRequest request) throws GraphRunnerException {

        logger.info("Resume: sessionId={}, action={}", request.sessionId(), request.action());
        return resume(request.sessionId(), request.action(), request.comment(), request.modifiedSql());
    }

    /**
     * 获取所有等待审批的任务
     */
    @GetMapping("/pending")
    public Collection<ApprovalTask> getPendingTasks() {
        return new ArrayList<>(pendingTasks.values());
    }

    /**
     * 内部 resume 实现
     *
     * 核心流程：
     * 1. 根据 threadId 获取当前 StateSnapshot
     * 2. 构建包含人工反馈的 RunnableConfig
     * 3. 调用 compiledGraph.stream(null, resumeConfig) 从 checkpoint 恢复
     */
    private Flux<ServerSentEvent<Map<String, Object>>> resume(
            String sessionId,
            String action,
            String comment,
            String modifiedSql) throws GraphRunnerException {

        RunnableConfig baseConfig = RunnableConfig.builder().threadId(sessionId).build();
        Optional<StateSnapshot> stateSnapshot = compiledGraph.stateOf(baseConfig);

        if (stateSnapshot.isEmpty()) {
            logger.error("未找到会话状态: {}", sessionId);
            return Flux.just(ServerSentEvent.<Map<String, Object>>builder()
                    .data(Map.of("error", "未找到对应的会话状态: " + sessionId))
                    .build());
        }

        // 获取中断节点的 ID（对应 InterruptionMetadata.node()）
        String interruptedNode = stateSnapshot.get().next();

        // 用 updateState 将人工反馈写入 checkpoint，传入中断节点 ID
        Map<String, Object> stateUpdates = new HashMap<>();
        stateUpdates.put("human_action", action);
        if (comment != null) stateUpdates.put("human_comment", comment);
        if (modifiedSql != null) {
            stateUpdates.put("modified_sql", modifiedSql);
            stateUpdates.put("executed_sql", modifiedSql);
        }

        RunnableConfig updatedConfig;
        try {
            updatedConfig = compiledGraph.updateState(baseConfig, stateUpdates, interruptedNode);
        } catch (Exception e) {
            logger.error("updateState 失败: sessionId={}", sessionId, e);
            return Flux.just(ServerSentEvent.<Map<String, Object>>builder()
                    .data(Map.of("error", "状态更新失败: " + e.getMessage()))
                    .build());
        }

        // 不添加 HUMAN_FEEDBACK_METADATA_KEY
        // 原因：该 key 在整个 graph 执行期间不会自动清除，会导致循环回到
        // human_approval 时 interrupt() 被跳过，进而使 REJECT 后重新生成的
        // SQL 无法再次触发审批中断。改用 state 中的 human_action 来判断
        // resume vs 新周期：updateState 写入后 interrupt() 检测到非空 → 跳过
        // GenerateSqlNode 清空后 interrupt() 检测到空 → 正常中断
        RunnableConfig resumeConfig = RunnableConfig.builder(updatedConfig)
                .build();

        // 清除等待中的任务
        pendingTasks.remove(sessionId);

        // 注册流式事件 Sink — 用于推送 Node 内部的 token 级输出（如 summarize）
        Sinks.Many<Map<String, Object>> streamSink = streamingEventBus.register(sessionId);

        Sinks.Many<ServerSentEvent<Map<String, Object>>> sink =
                Sinks.many().unicast().onBackpressureBuffer();

        // 从 checkpoint 恢复执行
        Flux<NodeOutput> nodeOutputFlux = compiledGraph.stream(null, resumeConfig);

        nodeOutputFlux.subscribe(
                output -> {
                    // 跳过 END 节点输出 — 与主控制器相同的去重逻辑
                    if (StateGraph.END.equals(output.node()) || "__END__".equals(output.node())) {
                        logger.debug("跳过 END 节点输出: node={}, sessionId={}", output.node(), sessionId);
                        return;
                    }

                    Map<String, Object> eventData = new LinkedHashMap<>();
                    eventData.put("node", output.node());

                    if (output instanceof InterruptionMetadata interruptionMeta) {
                        eventData.put("type", "interruption");
                        eventData.put("message", "SQL 已生成并校验完毕，等待您的审批:");

                        // 必须传递 metadata，前端依赖 metadata.type === 'SQL_APPROVAL' 来渲染审批面板
                        Map<String, Object> meta = new LinkedHashMap<>();
                        interruptionMeta.metadata().ifPresent(meta::putAll);
                        eventData.put("metadata", meta);

                        // 重新注册为待审批任务
                        registerPendingTask(sessionId, meta);
                    } else {
                        eventData.put("type", "node_output");
                        eventData.put("state", output.state().data());
                        logger.info("[SSE NodeOutput-Resume] node={}, sessionId={}, hasSummary={}",
                                output.node(), sessionId,
                                output.state().data().containsKey("summary"));
                    }

                    sink.tryEmitNext(ServerSentEvent.<Map<String, Object>>builder()
                            .data(eventData)
                            .build());
                },
                error -> {
                    logger.error("Resume 执行出错: sessionId={}", sessionId, error);
                    streamingEventBus.unregister(sessionId);
                    sink.tryEmitError(error);
                },
                () -> {
                    logger.info("Resume 执行完成: sessionId={}", sessionId);
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
                .doOnError(e -> logger.error("SSE Resume 流异常: sessionId={}", sessionId, e));
    }

    /**
     * 注册等待审批的任务（由主控制器在检测到 interrupt 后调用）
     */
    public void registerPendingTask(String sessionId, Map<String, Object> interruptionMetadata) {
        ApprovalTask task = new ApprovalTask(
                sessionId,
                (String) interruptionMetadata.getOrDefault("user_question", ""),
                (String) interruptionMetadata.getOrDefault("generated_sql", ""),
                (String) interruptionMetadata.getOrDefault("risk_level", "MEDIUM"),
                (String) interruptionMetadata.getOrDefault("tables_used", "").toString(),
                interruptionMetadata
        );
        pendingTasks.put(sessionId, task);
        logger.info("注册待审批任务: sessionId={}", sessionId);
    }

    // ===== 请求/响应 DTO =====

    public record ApprovalRequest(String sessionId, String comment) {}

    public record ModifyRequest(String sessionId, String modifiedSql, String comment) {}

    public record ResumeRequest(String sessionId, String action, String comment, String modifiedSql) {}

    public record ApprovalTask(
            String sessionId,
            String userQuestion,
            String generatedSql,
            String riskLevel,
            String tablesUsed,
            Map<String, Object> rawMetadata
    ) {}
}
