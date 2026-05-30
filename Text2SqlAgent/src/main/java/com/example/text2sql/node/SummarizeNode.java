package com.example.text2sql.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.example.text2sql.agent.SqlAgentPrompt;
import com.example.text2sql.streaming.StreamingEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 结果总结节点
 *
 * 功能：
 * - 对查询结果进行自然语言总结
 * - 向用户展示关键信息
 *
 * 输入: user_question, executed_sql, query_results, result_row_count
 * 输出: summary
 */
public class SummarizeNode implements AsyncNodeActionWithConfig {

    private static final Logger logger = LoggerFactory.getLogger(SummarizeNode.class);
    private final ChatClient chatClient;
    private final StreamingEventBus streamingEventBus;

    public SummarizeNode(ChatClient.Builder chatClientBuilder, StreamingEventBus streamingEventBus) {
        this.chatClient = chatClientBuilder.build();
        this.streamingEventBus = streamingEventBus;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state, RunnableConfig config) {
        logger.info("=== SummarizeNode: 流式生成结果总结 ===");

        String userQuestion = state.value("user_question", "");
        String executedSql = state.value("executed_sql", "");
        String sessionId = state.value("session_id", "");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> queryResults = state.value("query_results", new ArrayList<>());

        Integer rowCount = state.value("result_row_count", 0);

        // 截断结果数据用于总结（避免 token 过多）
        List<Map<String, Object>> previewData = queryResults.size() > 20
                ? queryResults.subList(0, 20)
                : queryResults;

        if (queryResults.isEmpty()) {
            String summary = "查询未返回任何数据。可能原因：\n"
                    + "- 数据库中没有符合条件的数据\n"
                    + "- 查询条件过于严格\n"
                    + "- 数据尚未录入系统\n\n"
                    + "建议：检查查询条件或联系数据管理员确认数据状态。";

            return CompletableFuture.completedFuture(Map.of(
                    "summary", summary,
                    "workflow_status", "COMPLETED",
                    "current_node", "summarize"
            ));
        }

        String prompt = SqlAgentPrompt.SUMMARIZE_PROMPT
                .replace("{user_question}", userQuestion)
                .replace("{executed_sql}", executedSql)
                .replace("{row_count}", String.valueOf(rowCount))
                .replace("{query_results}", previewData.toString());

        // 通知前端开始流式输出
        logger.info("[SummarizeNode] 开始流式生成总结, sessionId={}, rowCount={}", sessionId, rowCount);
        streamingEventBus.emitStart(sessionId, "summarize", "正在生成总结...");

        Flux<String> streamFlux = this.chatClient.prompt()
                .user(prompt)
                .stream()
                .content();

        return streamFlux
                .doOnNext(token -> streamingEventBus.emit(sessionId, "summarize", token))
                .collect(Collectors.joining())
                .map(summary -> {
                    logger.info("[SummarizeNode] 流式生成完成, summaryLength={}, sessionId={}",
                            summary.length(), sessionId);
                    streamingEventBus.emitEnd(sessionId, "summarize");

                    Map<String, Object> result = new HashMap<>();
                    result.put("summary", summary);
                    result.put("workflow_status", "COMPLETED");
                    result.put("current_node", "summarize");

                    return result;
                })
                .onErrorResume(e -> {
                    logger.error("SummarizeNode 总结生成失败", e);
                    String fallbackSummary = String.format(
                            "查询返回了 %d 行数据。\nSQL: %s\n\n前几行数据: %s",
                            rowCount, executedSql,
                            queryResults.size() > 5 ? queryResults.subList(0, 5).toString() : queryResults.toString()
                    );
                    return reactor.core.publisher.Mono.just(Map.of(
                            "summary", (Object) fallbackSummary,
                            "workflow_status", "COMPLETED",
                            "current_node", "summarize"
                    ));
                })
                .toFuture();
    }
}
