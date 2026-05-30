package com.example.text2sql.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 获取表结构定义和示例数据的 Tool
 *
 * 输入表名列表，返回每张表的列定义(名称、类型、是否可空)
 * 以及前 3 行示例数据，帮助 LLM 理解表结构以生成正确的 SQL。
 */
@Component
public class GetSchemaTool {

    private static final Logger logger = LoggerFactory.getLogger(GetSchemaTool.class);

    private final JdbcTemplate jdbcTemplate;

    public GetSchemaTool(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Tool(description = """
            获取指定表的完整结构信息，包括：
            1. 每列的字段名、数据类型、是否可空、默认值
            2. 前 3 行示例数据
            用于在生成 SQL 前了解表结构和数据格式。
            """)
    public Map<String, Object> getSchema(
            @ToolParam(description = "需要获取结构的表名列表，用逗号分隔，例如: 'orders,users,products'")
            String tables) {

        logger.info("Tool[get_schema] 被调用: tables={}", tables);

        List<String> tableList = Arrays.stream(tables.split(","))
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> tableInfos = new ArrayList<>();

        for (String tableName : tableList) {
            try {
                // 获取列信息
                List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                        "SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, " +
                        "IS_NULLABLE, COLUMN_DEFAULT " +
                        "FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = ? " +
                        "ORDER BY ORDINAL_POSITION",
                        tableName
                );

                // 获取示例数据
                List<Map<String, Object>> sampleData = List.of();
                try {
                    sampleData = jdbcTemplate.queryForList(
                            "SELECT * FROM " + tableName + " LIMIT 3"
                    );
                } catch (Exception e) {
                    logger.warn("获取表 {} 样本数据失败: {}", tableName, e.getMessage());
                }

                Map<String, Object> tableInfo = new LinkedHashMap<>();
                tableInfo.put("table_name", tableName);
                tableInfo.put("columns", columns);
                tableInfo.put("sample_data", sampleData);
                tableInfo.put("row_count", columns.size());
                tableInfos.add(tableInfo);

            } catch (Exception e) {
                logger.error("获取表 {} 结构失败: {}", tableName, e.getMessage());
                Map<String, Object> errorInfo = new LinkedHashMap<>();
                errorInfo.put("table_name", tableName);
                errorInfo.put("error", e.getMessage());
                tableInfos.add(errorInfo);
            }
        }

        result.put("tables", tableInfos);
        result.put("total_tables", tableInfos.size());

        logger.info("Tool[get_schema] 返回 {} 个表的结构", tableInfos.size());
        return result;
    }
}
