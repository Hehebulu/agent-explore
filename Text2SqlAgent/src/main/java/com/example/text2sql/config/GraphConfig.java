package com.example.text2sql.config;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;

import com.example.text2sql.dispatcher.ApprovalDispatcher;
import com.example.text2sql.node.*;
import com.example.text2sql.streaming.StreamingEventBus;
import com.example.text2sql.tool.ListTablesTool;
import com.example.text2sql.tool.GetSchemaTool;
import com.example.text2sql.tool.ExecuteQueryTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Text2SQL Agent Graph 工作流配置
 *
 * 核心工作流:
 * START → UserQuestion → ListTables → GetSchema → GenerateSql
 * → HumanApproval → ExecuteSql → Summarize → END
 *
 * 各 Node 通过 Tool 类执行实际逻辑：
 * - ListTablesNode  → ListTablesTool  → JdbcTemplate
 * - GetSchemaNode   → GetSchemaTool   → JdbcTemplate
 * - ExecuteSqlNode  → ExecuteQueryTool → JdbcTemplate + SqlSecurityValidator
 */
@Configuration
public class GraphConfig {

    private static final Logger logger = LoggerFactory.getLogger(GraphConfig.class);

    @Bean
    public StateGraph text2sqlGraph(
            ChatClient.Builder chatClientBuilder,
            ListTablesTool listTablesTool,
            GetSchemaTool getSchemaTool,
            ExecuteQueryTool executeQueryTool,
            StreamingEventBus streamingEventBus) throws GraphStateException {

        // ===== KeyStrategy 配置 - 参考 Spring AI Alibaba Graph 官方示例 =====
        // 使用 lambda + HashMap 方式定义，所有状态字段统一使用 REPLACE 策略
        KeyStrategyFactory keyStrategyFactory = () -> {
            HashMap<String, KeyStrategy> keyStrategyHashMap = new HashMap<>();
            // 会话标识
            keyStrategyHashMap.put("session_id", new ReplaceStrategy());
            // 用户输入
            keyStrategyHashMap.put("user_question", new ReplaceStrategy());
            keyStrategyHashMap.put("chat_history", new ReplaceStrategy());
            // 数据库发现
            keyStrategyHashMap.put("available_tables", new ReplaceStrategy());
            keyStrategyHashMap.put("table_schemas", new ReplaceStrategy());
            // SQL 生成
            keyStrategyHashMap.put("generated_sql", new ReplaceStrategy());
            // 人工审批
            keyStrategyHashMap.put("human_action", new ReplaceStrategy());
            keyStrategyHashMap.put("modified_sql", new ReplaceStrategy());
            keyStrategyHashMap.put("human_comment", new ReplaceStrategy());
            keyStrategyHashMap.put("approval_cycle_id", new ReplaceStrategy());
            // SQL 执行
            keyStrategyHashMap.put("executed_sql", new ReplaceStrategy());
            keyStrategyHashMap.put("query_results", new ReplaceStrategy());
            keyStrategyHashMap.put("result_row_count", new ReplaceStrategy());
            // 结果
            keyStrategyHashMap.put("summary", new ReplaceStrategy());
            // 工作流状态
            keyStrategyHashMap.put("workflow_status", new ReplaceStrategy());
            keyStrategyHashMap.put("error_message", new ReplaceStrategy());
            keyStrategyHashMap.put("next_node", new ReplaceStrategy());
            keyStrategyHashMap.put("tables_used", new ReplaceStrategy());
            keyStrategyHashMap.put("intent", new ReplaceStrategy());
            // 追踪
            keyStrategyHashMap.put("current_node", new ReplaceStrategy());
            return keyStrategyHashMap;
        };

        // ===== 创建节点 — 注入对应的 Tool =====
        var userQuestionNode = new UserQuestionNode(chatClientBuilder);
        var listTablesNode = new ListTablesNode(listTablesTool);
        var getSchemaNode = new GetSchemaNode(getSchemaTool);
        var generateSqlNode = new GenerateSqlNode(chatClientBuilder, streamingEventBus);
        var humanApprovalNode = new HumanApprovalNode();
        var executeSqlNode = new ExecuteSqlNode(executeQueryTool);
        var summarizeNode = new SummarizeNode(chatClientBuilder, streamingEventBus);

        // ===== 构建 StateGraph =====
        StateGraph stateGraph = new StateGraph(keyStrategyFactory)
                // 注册所有节点
                .addNode("user_question", userQuestionNode)
                .addNode("list_tables", listTablesNode)
                .addNode("get_schema", getSchemaNode)
                .addNode("generate_sql", generateSqlNode)
                .addNode("human_approval", humanApprovalNode)
                .addNode("execute_sql", executeSqlNode)
                .addNode("summarize", summarizeNode)

                // 构建边
                .addEdge(StateGraph.START, "user_question")
                .addEdge("user_question", "list_tables")
                .addEdge("list_tables", "get_schema")
                .addEdge("get_schema", "generate_sql")
                .addEdge("generate_sql", "human_approval")
                .addConditionalEdges("human_approval",
                        AsyncEdgeAction.edge_async(new ApprovalDispatcher()),
                        Map.of("execute_sql", "execute_sql",
                                "generate_sql", "generate_sql",
                                "human_approval", "human_approval",
                                StateGraph.END, StateGraph.END))
                .addEdge("execute_sql", "summarize")
                .addEdge("summarize", StateGraph.END);

        // 打印 Graph PlantUML 可视化
        GraphRepresentation representation = stateGraph.getGraph(
                GraphRepresentation.Type.PLANTUML,
                "Text2SQL Agent with Human-in-the-Loop");
        logger.info("\n========== Text2SQL Agent Graph ==========\n{}\n==========================================\n",
                representation.content());

        return stateGraph;
    }

    @Bean
    public CompiledGraph compiledText2sqlGraph(StateGraph text2sqlGraph) throws GraphStateException {
        SaverConfig saverConfig = SaverConfig.builder()
                .register(new MemorySaver())
                .build();

        CompiledGraph compiledGraph = text2sqlGraph.compile(
                CompileConfig.builder()
                        .saverConfig(saverConfig)
                        .build()
        );

        logger.info("CompiledGraph bean 创建完成，由 HumanApprovalNode.interrupt() 控制中断");
        return compiledGraph;
    }
}
