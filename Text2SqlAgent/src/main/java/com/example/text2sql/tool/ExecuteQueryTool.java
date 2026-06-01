package com.example.text2sql.tool;

import com.example.text2sql.security.SqlSecurityValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * SQL 执行 Tool — 执行经过审批的 SELECT 查询
 *
 * 在执行前会调用 SqlSecurityValidator 做最终安全检查，
 * 并自动添加 LIMIT 限制防止返回过多数据。
 */
@Component
public class ExecuteQueryTool {

    private static final Logger logger = LoggerFactory.getLogger(ExecuteQueryTool.class);

    private final JdbcTemplate jdbcTemplate;
    private final SqlSecurityValidator securityValidator;

    private static final int MAX_RESULT_ROWS = 1000;

    public ExecuteQueryTool(JdbcTemplate jdbcTemplate,
                            SqlSecurityValidator securityValidator) {
        this.jdbcTemplate = jdbcTemplate;
        this.securityValidator = securityValidator;
    }

    @Tool(description = """
            执行 SELECT 查询并返回结果。
            注意：只允许执行 SELECT 语句，所有 DML/DDL 语句会被拒绝。
            执行前会进行安全校验，并自动限制最大返回行数。
            """)
    public Map<String, Object> executeQuery(
            @ToolParam(description = "要执行的 SELECT SQL 语句") String sql) {

        logger.info("Tool[execute_query] 被调用: sql={}", sql);

        // 1. 安全校验
        SqlSecurityValidator.ValidationResult validation = securityValidator.validate(sql);
        if (!validation.approved()) {
            logger.warn("Tool[execute_query] 安全检查拒绝: {}", validation.message());
            return Map.of(
                    "success", false,
                    "error", "SQL 安全检查未通过: " + validation.message(),
                    "violations", validation.violations(),
                    "results", List.of(),
                    "row_count", 0
            );
        }

        String finalSql = sql.trim();

        // 2. 自动添加 LIMIT
        String upperSql = finalSql.toUpperCase();
        if (!upperSql.contains("LIMIT") && !upperSql.contains("TOP")
                && !upperSql.contains("FETCH") && !upperSql.contains("ROWNUM")) {
            finalSql = finalSql.replaceAll(";\\s*$", "");
            finalSql += " LIMIT " + MAX_RESULT_ROWS;
            logger.info("Tool[execute_query] 自动追加 LIMIT {}", MAX_RESULT_ROWS);
        }

        // 3. 执行查询
        long startTime = System.currentTimeMillis();
        try {
            jdbcTemplate.setQueryTimeout(30);
            List<Map<String, Object>> results = jdbcTemplate.queryForList(finalSql);
            long elapsed = System.currentTimeMillis() - startTime;

            logger.info("Tool[execute_query] 执行成功: {} 行, {} ms", results.size(), elapsed);

            return Map.of(
                    "success", true,
                    "results", results,
                    "row_count", results.size(),
                    "execution_time_ms", elapsed,
                    "executed_sql", finalSql
            );

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            logger.error("Tool[execute_query] 执行失败 ({} ms): {}", elapsed, e.getMessage(), e);

            return Map.of(
                    "success", false,
                    "error", "SQL 执行失败: " + e.getMessage(),
                    "exception_type", e.getClass().getSimpleName(),
                    "exception_message", e.getMessage(),
                    "execution_time_ms", elapsed,
                    "results", List.of(),
                    "row_count", 0
            );
        }
    }
}
