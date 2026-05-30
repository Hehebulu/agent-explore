package com.example.text2sql.tool;

import com.example.text2sql.agent.SqlAgentPrompt;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * SQL 校验 Tool — 使用 LLM 检查 SQL 的正确性、安全性和性能
 *
 * 不仅做程序化关键字检查，更通过 LLM 做语义级别的审核：
 * - 表和列是否存在
 * - JOIN 条件是否合法
 * - 是否有危险操作
 * - 是否可能全表扫描
 * - 是否缺少 LIMIT
 */
@Component
public class CheckQueryTool {

    private static final Logger logger = LoggerFactory.getLogger(CheckQueryTool.class);
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CheckQueryTool(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Tool(description = """
            使用 LLM 检查 SQL 查询的正确性、安全性和性能。
            检查项包括：是否只读 SELECT、表和列是否存在、JOIN 是否正确、
            是否有全表扫描风险、是否缺少 LIMIT、是否有 SQL 注入风险。
            返回 JSON 格式的审核结果。
            """)
    public Map<String, Object> checkQuery(
            @ToolParam(description = "用户的原始自然语言问题") String userQuestion,
            @ToolParam(description = "待检查的 SQL 语句") String sql,
            @ToolParam(description = "相关的表结构信息（JSON 格式）") String tableSchemas) {

        logger.info("Tool[check_query] 被调用: sql={}", sql);

        // 1. 先做快速的程序化检查
        String fastCheck = fastSecurityCheck(sql);
        if (fastCheck != null) {
            logger.warn("Tool[check_query] 快速安全检查失败: {}", fastCheck);
            return Map.of(
                    "valid", false,
                    "risk_level", "CRITICAL",
                    "issues", List.of(fastCheck),
                    "suggestions", List.of("请修正 SQL 中的安全问题后重试"),
                    "corrected_sql", sql,
                    "summary", "安全检查失败: " + fastCheck
            );
        }

        // 2. LLM 深度审核
        try {
            String reviewPrompt = SqlAgentPrompt.SQL_REVIEWER_PROMPT
                    .replace("{user_question}", userQuestion)
                    .replace("{generated_sql}", sql)
                    .replace("{table_schemas}", tableSchemas);

            String response = this.chatClient.prompt()
                    .user(reviewPrompt)
                    .call()
                    .content();

            @SuppressWarnings("unchecked")
            Map<String, Object> reviewResult = objectMapper.readValue(
                    extractJson(response), Map.class);

            logger.info("Tool[check_query] LLM 审核完成: valid={}, risk={}",
                    reviewResult.get("valid"), reviewResult.get("risk_level"));
            return reviewResult;

        } catch (Exception e) {
            logger.error("Tool[check_query] LLM 调用失败", e);
            return Map.of(
                    "valid", true,
                    "risk_level", "MEDIUM",
                    "issues", List.of(),
                    "suggestions", List.of("LLM 审核暂时不可用，建议人工审核"),
                    "corrected_sql", sql,
                    "summary", "LLM 审核服务暂时不可用，请人工审核"
            );
        }
    }

    /**
     * 快速程序化安全检查
     */
    private String fastSecurityCheck(String sql) {
        if (sql == null || sql.trim().isEmpty()) return "SQL 为空";

        String upperSql = sql.toUpperCase().trim();

        if (!upperSql.startsWith("SELECT") && !upperSql.startsWith("WITH")) {
            return "只允许 SELECT 或 WITH (CTE) 开头的查询";
        }

        String[] dangerous = {"INSERT ", "DELETE ", "UPDATE ", "DROP ", "TRUNCATE ",
                "ALTER ", "CREATE ", "REPLACE ", "MERGE ", "GRANT ", "REVOKE ",
                "EXEC ", "EXECUTE ", "CALL ", "LOAD_FILE", "INTO OUTFILE",
                "INTO DUMPFILE", "SLEEP(", "BENCHMARK(", "WAITFOR DELAY"};

        for (String keyword : dangerous) {
            if (upperSql.contains(keyword)) {
                return "SQL 包含禁止的关键字: " + keyword.trim();
            }
        }

        // 检查分号注入
        long semicolons = sql.chars().filter(c -> c == ';').count();
        if (semicolons > 1) return "SQL 包含多个分号，疑似多语句注入";

        return null;
    }

    private String extractJson(String response) {
        int start = response.indexOf("{");
        int end = response.lastIndexOf("}");
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return response;
    }
}
