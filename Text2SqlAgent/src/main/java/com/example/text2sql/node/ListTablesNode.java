package com.example.text2sql.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.example.text2sql.tool.ListTablesTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 列出数据库所有表的节点
 *
 * 功能：
 * - 调用 ListTablesTool 获取数据库表列表
 * - 输出表名列表到 State
 *
 * 输入: dbConfig (schema)
 * 输出: available_tables
 */
public class ListTablesNode implements AsyncNodeActionWithConfig {

    private static final Logger logger = LoggerFactory.getLogger(ListTablesNode.class);
    private final ListTablesTool listTablesTool;

    public ListTablesNode(ListTablesTool listTablesTool) {
        this.listTablesTool = listTablesTool;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state, RunnableConfig config) {
        logger.info("=== ListTablesNode: 获取数据库表列表 ===");

        try {
            List<String> tables = listTablesTool.listTables(null);

            logger.info("发现 {} 个表: {}", tables.size(), tables);

            Map<String, Object> result = new HashMap<>();
            result.put("available_tables", tables);
            result.put("workflow_status", "DISCOVERING");
            result.put("current_node", "list_tables");

            return CompletableFuture.completedFuture(result);

        } catch (Exception e) {
            logger.error("ListTablesNode 获取表列表失败", e);
            return CompletableFuture.completedFuture(Map.of(
                    "workflow_status", "FAILED",
                    "error_message", "获取表列表失败: " + e.getMessage(),
                    "available_tables", List.of()
            ));
        }
    }
}
