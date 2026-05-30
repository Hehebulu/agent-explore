package com.example.text2sql.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.example.text2sql.agent.SqlAgentPrompt;
import com.example.text2sql.streaming.StreamingEventBus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * SQL 生成节点 — 支持流式输出
 *
 * 功能：
 * - 根据用户问题 + 表结构 + 示例数据
 * - 调用 LLM 流式生成 SQL，实时推送到前端
 * - 强约束：只生成 SELECT 语句
 *
 * 输入: user_question, table_schemas, intent
 * 输出: generated_sql, tables_used
 */
public class GenerateSqlNode implements AsyncNodeActionWithConfig {

    private static final Logger logger = LoggerFactory.getLogger(GenerateSqlNode.class);
    private final ChatClient chatClient;
    private final StreamingEventBus streamingEventBus;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GenerateSqlNode(ChatClient.Builder chatClientBuilder, StreamingEventBus streamingEventBus) {
        this.chatClient = chatClientBuilder.build();
        this.streamingEventBus = streamingEventBus;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state, RunnableConfig config) {
        logger.info("=== GenerateSqlNode: 流式生成 SQL ===");

        String userQuestion = state.value("user_question", "");
        String intent = state.value("intent", "");
        String sessionId = state.value("session_id", "");

        @SuppressWarnings("unchecked")
        Map<String, String> tableSchemas = state.value("table_schemas", new LinkedHashMap<>());

        if (tableSchemas.isEmpty()) {
            logger.error("没有可用的表结构信息");
            return CompletableFuture.completedFuture(Map.of(
                    "workflow_status", "FAILED",
                    "error_message", "无法获取数据库表结构，请检查数据库连接",
                    "generated_sql", ""
            ));
        }

        // 构建生成 SQL 的 Prompt
        StringBuilder schemaInfo = new StringBuilder();
        for (Map.Entry<String, String> entry : tableSchemas.entrySet()) {
            schemaInfo.append("--- Table: ").append(entry.getKey()).append(" ---\n");
            schemaInfo.append(entry.getValue()).append("\n");
        }

        String prompt = SqlAgentPrompt.SYSTEM_PROMPT + "\n\n" +
                "## User Question\n" + userQuestion + "\n\n" +
                "## Intent Analysis\n" + intent + "\n\n" +
                "## Available Tables and Schemas\n" + schemaInfo + "\n\n" +
                "Generate the SQL query based on the above information.";

        // 通知前端开始流式输出
        streamingEventBus.emitStart(sessionId, "generate_sql", "正在生成 SQL...");

        Flux<String> streamFlux = this.chatClient.prompt()
                .system(SqlAgentPrompt.SYSTEM_PROMPT)
                .user(prompt)
                .stream()
                .content();

        return streamFlux
                .doOnNext(token -> streamingEventBus.emit(sessionId, "generate_sql", token))
                .collect(Collectors.joining())
                .map(fullResponse -> {
                    logger.info("LLM 流式生成完成，总长度: {}", fullResponse.length());
                    streamingEventBus.emitEnd(sessionId, "generate_sql");

                    String generatedSql = fullResponse;
                    List<String> tablesUsed = new ArrayList<>();

                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> responseMap = objectMapper.readValue(extractJson(fullResponse), Map.class);
                        generatedSql = (String) responseMap.getOrDefault("sql", fullResponse);
                        tablesUsed = (List<String>) responseMap.getOrDefault("tables_used", new ArrayList<>());
                    } catch (Exception e) {
                        generatedSql = extractSqlFromText(fullResponse);
                        logger.warn("无法解析 JSON 格式的响应，提取 SQL: {}", generatedSql);
                    }

                    Map<String, Object> result = new HashMap<>();
                    result.put("generated_sql", generatedSql);
                    result.put("tables_used", tablesUsed);
                    result.put("workflow_status", "CHECKING");
                    result.put("current_node", "generate_sql");
                    // 每次生成 SQL 都是新的审批周期，清空上一轮的审批状态
                    // 防止 REJECT 后循环回来时 state 中残留旧的 transient 值
                    result.put("human_action", "");
                    result.put("human_comment", "");
                    result.put("next_node", "");
                    result.put("error_message", "");
                    result.put("query_results", new ArrayList<>());
                    // 审批周期 ID：用于 HumanApprovalNode 判断是"同一轮 resume"
                    // 还是"循环回来后的新周期"
                    result.put("approval_cycle_id", UUID.randomUUID().toString());

                    return result;
                })
                .onErrorResume(e -> {
                    logger.error("GenerateSqlNode LLM 调用失败", e);
                    streamingEventBus.emitEnd(sessionId, "generate_sql");
                    return reactor.core.publisher.Mono.just(Map.of(
                            "generated_sql", (Object) "",
                            "tables_used", (Object) new ArrayList<>(),
                            "workflow_status", "FAILED",
                            "error_message", "SQL 生成失败: " + e.getMessage(),
                            "approval_cycle_id", UUID.randomUUID().toString(),
                            "current_node", "generate_sql"
                    ));
                })
                .toFuture();
    }

    private String extractJson(String response) {
        int start = response.indexOf("{");
        int end = response.lastIndexOf("}");
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return response;
    }

    private String extractSqlFromText(String response) {
        // 尝试从 Markdown 代码块提取 SQL
        if (response.contains("```sql")) {
            int start = response.indexOf("```sql") + 6;
            int end = response.indexOf("```", start);
            if (end > start) {
                return response.substring(start, end).trim();
            }
        }
        if (response.contains("```")) {
            int start = response.indexOf("```") + 3;
            int end = response.indexOf("```", start);
            if (end > start) {
                return response.substring(start, end).trim();
            }
        }
        return response;
    }
}
