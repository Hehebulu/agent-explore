package com.example.text2sql.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.example.text2sql.tool.ExecuteQueryTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * SQL 执行节点
 *
 * 功能：
 * - 在通过人工审批后，调用 ExecuteQueryTool 执行最终 SQL
 * - 记录执行结果和执行时间
 *
 * 输入: executed_sql
 * 输出: query_results, result_row_count
 *
 * 安全由 ExecuteQueryTool (SqlSecurityValidator) 保障
 */
public class ExecuteSqlNode implements AsyncNodeActionWithConfig {

    private static final Logger logger = LoggerFactory.getLogger(ExecuteSqlNode.class);
    private final ExecuteQueryTool executeQueryTool;

    public ExecuteSqlNode(ExecuteQueryTool executeQueryTool) {
        this.executeQueryTool = executeQueryTool;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state, RunnableConfig config) {
        logger.info("=== ExecuteSqlNode: 执行 SQL ===");

        String executedSql = state.value("executed_sql", "");

        // 能进入 execute_sql，说明 ApprovalDispatcher 已确认审批通过
        // 不需要再检查 human_action（避免 REJECT 状态污染后续审批）

        if (executedSql.isEmpty()) {
            logger.error("待执行的 SQL 为空");
            return CompletableFuture.completedFuture(Map.of(
                    "workflow_status", "FAILED",
                    "error_message", "待执行的 SQL 为空"
            ));
        }

        // 通过 Tool 执行（Tool 内部做安全校验 + LIMIT + 执行）
        Map<String, Object> execResult = executeQueryTool.executeQuery(executedSql);

        boolean success = (boolean) execResult.getOrDefault("success", false);

        if (success) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results =
                    (List<Map<String, Object>>) execResult.getOrDefault("results", List.of());
            int rowCount = (int) execResult.getOrDefault("row_count", 0);
            logger.info("ExecuteSqlNode 执行完成: {} 行", rowCount);

            Map<String, Object> result = new HashMap<>();
            result.put("query_results", results);
            result.put("result_row_count", rowCount);
            result.put("workflow_status", "SUMMARIZING");
            result.put("current_node", "execute_sql");

            return CompletableFuture.completedFuture(result);
        } else {
            String error = (String) execResult.getOrDefault("error", "未知错误");

            @SuppressWarnings("unchecked")
            List<String> violations = (List<String>) execResult.getOrDefault("violations", List.of());
            if (!violations.isEmpty()) {
                error += " | 安全违规: " + String.join(", ", violations);
            }

            logger.error("ExecuteSqlNode 执行失败: {}", error);

            return CompletableFuture.completedFuture(Map.of(
                    "workflow_status", "FAILED",
                    "error_message", error,
                    "query_results", List.of(),
                    "result_row_count", 0
            ));
        }
    }
}
