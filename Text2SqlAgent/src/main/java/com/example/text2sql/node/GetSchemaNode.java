package com.example.text2sql.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.example.text2sql.tool.GetSchemaTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 获取表结构信息的节点
 *
 * 功能：
 * - 调用 GetSchemaTool 获取表结构定义和示例数据
 * - 输出 schema 和 sample data 到 State
 *
 * 输入: available_tables
 * 输出: table_schemas
 */
public class GetSchemaNode implements AsyncNodeActionWithConfig {

    private static final Logger logger = LoggerFactory.getLogger(GetSchemaNode.class);
    private final GetSchemaTool getSchemaTool;

    public GetSchemaNode(GetSchemaTool getSchemaTool) {
        this.getSchemaTool = getSchemaTool;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state, RunnableConfig config) {
        logger.info("=== GetSchemaNode: 获取表结构 ===");

        @SuppressWarnings("unchecked")
        List<String> availableTables = state.value("available_tables", new ArrayList<>());

        if (availableTables.isEmpty()) {
            logger.warn("没有可用的表");
            return CompletableFuture.completedFuture(Map.of(
                    "table_schemas", Map.of()
            ));
        }

        try {
            // 通过 Tool 获取表结构
            String tablesParam = String.join(",", availableTables);
            Map<String, Object> toolResult = getSchemaTool.getSchema(tablesParam);

            // 解析 Tool 返回结果，转换为 Node 需要的格式
            Map<String, String> tableSchemas = new LinkedHashMap<>();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tableInfos =
                    (List<Map<String, Object>>) toolResult.get("tables");

            if (tableInfos != null) {
                for (Map<String, Object> tableInfo : tableInfos) {
                    String tableName = (String) tableInfo.get("table_name");

                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> columns =
                            (List<Map<String, Object>>) tableInfo.get("columns");

                    StringBuilder schemaBuilder = new StringBuilder();
                    schemaBuilder.append("Table: ").append(tableName).append("\n");
                    schemaBuilder.append("Columns:\n");
                    if (columns != null) {
                        for (Map<String, Object> col : columns) {
                            schemaBuilder.append(String.format("  - %s (%s) %s",
                                    col.get("COLUMN_NAME"),
                                    col.get("DATA_TYPE"),
                                    "YES".equals(col.get("IS_NULLABLE")) ? "NULL" : "NOT NULL"
                            ));
                            if (col.get("COLUMN_DEFAULT") != null) {
                                schemaBuilder.append(" DEFAULT ").append(col.get("COLUMN_DEFAULT"));
                            }
                            schemaBuilder.append("\n");
                        }
                    }
                    tableSchemas.put(tableName, schemaBuilder.toString());
                }
            }

            logger.info("成功获取 {} 个表的结构", tableSchemas.size());

            Map<String, Object> result = new HashMap<>();
            result.put("table_schemas", tableSchemas);
            result.put("workflow_status", "GENERATING");
            result.put("current_node", "get_schema");

            return CompletableFuture.completedFuture(result);

        } catch (Exception e) {
            logger.error("GetSchemaNode 获取表结构失败", e);
            return CompletableFuture.completedFuture(Map.of(
                    "workflow_status", "FAILED",
                    "error_message", "获取表结构失败: " + e.getMessage(),
                    "table_schemas", Map.of()
            ));
        }
    }
}
