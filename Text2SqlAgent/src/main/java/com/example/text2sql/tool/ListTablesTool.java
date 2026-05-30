package com.example.text2sql.tool;

import com.example.text2sql.config.DbConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 列出数据库中所有用户表的 Tool
 *
 * 供 Agent / LLM 通过 Tool Calling 自动调用，
 * 也可被 ListTablesNode 直接注入使用。
 */
@Component
public class ListTablesTool {

    private static final Logger logger = LoggerFactory.getLogger(ListTablesTool.class);

    private final JdbcTemplate jdbcTemplate;
    private final DbConfig dbConfig;

    public ListTablesTool(JdbcTemplate jdbcTemplate, DbConfig dbConfig) {
        this.jdbcTemplate = jdbcTemplate;
        this.dbConfig = dbConfig;
    }

    @Tool(description = "列出数据库中所有用户表。返回表名列表，用于了解数据库中有哪些表可用。")
    public List<String> listTables(
            @ToolParam(description = "数据库 schema 名称，默认 PUBLIC") String schemaName) {

        logger.info("Tool[list_tables] 被调用: schema={}", schemaName);

        String schema = (schemaName != null && !schemaName.isBlank())
                ? schemaName
                : dbConfig.getSchema();

        // H2 MySQL 模式下 INFORMATION_SCHEMA 的 TABLE_SCHEMA 可能大小写敏感，
        // 使用 UPPER 比较 + TABLE_TYPE IN ('TABLE', 'BASE TABLE') 确保兼容
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES " +
                "WHERE UPPER(TABLE_SCHEMA) = UPPER(?) " +
                "AND TABLE_TYPE IN ('TABLE', 'BASE TABLE') " +
                "ORDER BY TABLE_NAME",
                String.class,
                schema
        );

        logger.info("Tool[list_tables] 返回 {} 个表: {}", tables.size(), tables);
        return tables;
    }
}
