package com.example.text2sql.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.example.text2sql.tool.CheckQueryTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * SQL 校验节点
 *
 * 功能：
 * - 调用 CheckQueryTool 检查 SQL 合法性、安全性、性能
 * - CheckQueryTool 内部做 程序化检查 + LLM 深度审核
 *
 * 输入: generated_sql, user_question, table_schemas
 * 输出: sql_valid, sql_check_result, sql_check_suggestion, risk_level
 */
public class CheckSqlNode implements AsyncNodeActionWithConfig {

    private static final Logger logger = LoggerFactory.getLogger(CheckSqlNode.class);
    private final CheckQueryTool checkQueryTool;

    public CheckSqlNode(CheckQueryTool checkQueryTool) {
        this.checkQueryTool = checkQueryTool;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state, RunnableConfig config) {
        logger.info("=== CheckSqlNode: 校验 SQL ===");

        String userQuestion = state.value("user_question", "");
        String generatedSql = state.value("generated_sql", "");

        @SuppressWarnings("unchecked")
        Map<String, String> tableSchemas = state.value("table_schemas", new LinkedHashMap<>());

        if (generatedSql.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of(
                    "sql_valid", false,
                    "sql_check_result", "SQL 为空，无法继续",
                    "risk_level", "CRITICAL"
            ));
        }

        // 通过 Tool 校验 (Tool 内部做快速安全检查 + LLM 审核)
        Map<String, Object> reviewResult = checkQueryTool.checkQuery(
                userQuestion,
                generatedSql,
                tableSchemas.toString()
        );

        boolean valid = (boolean) reviewResult.getOrDefault("valid", false);
        String riskLevel = (String) reviewResult.getOrDefault("risk_level", "MEDIUM");
        String summary = (String) reviewResult.getOrDefault("summary", "");

        @SuppressWarnings("unchecked")
        List<String> issues = (List<String>) reviewResult.getOrDefault("issues", List.of());
        @SuppressWarnings("unchecked")
        List<String> suggestions = (List<String>) reviewResult.getOrDefault("suggestions", List.of());
        String correctedSql = (String) reviewResult.getOrDefault("corrected_sql", generatedSql);

        logger.info("CheckSqlNode 校验完成: valid={}, risk={}", valid, riskLevel);

        Map<String, Object> result = new HashMap<>();
        result.put("sql_valid", valid);
        result.put("sql_check_result", summary);
        result.put("sql_check_suggestion", String.join("; ", suggestions));
        result.put("risk_level", riskLevel);
        result.put("workflow_status", "WAITING_APPROVAL");
        result.put("current_node", "check_sql");

        // 如果有修正后的 SQL，更新
        if (correctedSql != null && !correctedSql.equals(generatedSql)) {
            result.put("generated_sql", correctedSql);
        }

        return CompletableFuture.completedFuture(result);
    }
}
