package com.example.customeragent.graph;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.example.customeragent.graph.dispatcher.*;
import com.example.customeragent.graph.node.*;
import com.example.customeragent.repository.HumanQueueRepository;
import com.example.customeragent.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class GraphConfig {

    private static final Logger logger = LoggerFactory.getLogger(GraphConfig.class);

    @Bean
    public StateGraph customerAgentGraph(
            ChatClient.Builder chatClientBuilder,
            SecurityCheckService securityCheckService,
            EmotionAnalysisService emotionAnalysisService,
            FaqService faqService,
            IntentRecognitionService intentRecognitionService,
            SessionMemoryService sessionMemoryService,
            HumanQueueRepository humanQueueRepository) throws GraphStateException {

        KeyStrategyFactory keyStrategyFactory = () -> {
            HashMap<String, KeyStrategy> map = new HashMap<>();
            map.put("session_id", new ReplaceStrategy());
            map.put("user_id", new ReplaceStrategy());
            map.put("channel", new ReplaceStrategy());
            map.put("user_message", new ReplaceStrategy());
            map.put("security_blocked", new ReplaceStrategy());
            map.put("blocked_reason", new ReplaceStrategy());
            map.put("emotion_type", new ReplaceStrategy());
            map.put("emotion_score", new ReplaceStrategy());
            map.put("need_human", new ReplaceStrategy());
            map.put("transfer_reason", new ReplaceStrategy());
            map.put("faq_matched", new ReplaceStrategy());
            map.put("faq_question", new ReplaceStrategy());
            map.put("faq_answer", new ReplaceStrategy());
            map.put("faq_similarity", new ReplaceStrategy());
            map.put("intent", new ReplaceStrategy());
            map.put("confidence", new ReplaceStrategy());
            map.put("clarification_question", new ReplaceStrategy());
            map.put("clarify_count", new ReplaceStrategy());
            map.put("need_clarify", new ReplaceStrategy());
            map.put("biz_result", new ReplaceStrategy());
            map.put("human_queue_id", new ReplaceStrategy());
            map.put("human_queue_number", new ReplaceStrategy());
            map.put("human_wait_time", new ReplaceStrategy());
            map.put("ai_response", new ReplaceStrategy());
            map.put("workflow_status", new ReplaceStrategy());
            return map;
        };

        var securityCheckNode = new SecurityCheckNode(securityCheckService);
        var emotionNode = new EmotionNode(emotionAnalysisService);
        var faqNode = new FaqNode(faqService);
        var intentRecognitionNode = new IntentRecognitionNode(intentRecognitionService, sessionMemoryService);
        var bizDispatchNode = new BizDispatchNode(chatClientBuilder);
        var clarifyNode = new ClarifyNode();
        var llmChatNode = new LlmChatNode(chatClientBuilder);
        var humanTakeoverNode = new HumanTakeoverNode(humanQueueRepository);

        StateGraph stateGraph = new StateGraph(keyStrategyFactory)
                .addNode("security_check", securityCheckNode)
                .addNode("emotion_analysis", emotionNode)
                .addNode("faq_search", faqNode)
                .addNode("intent_recognition", intentRecognitionNode)
                .addNode("biz_dispatch", bizDispatchNode)
                .addNode("clarify", clarifyNode)
                .addNode("llm_chat", llmChatNode)
                .addNode("human_takeover", humanTakeoverNode)

                .addEdge(StateGraph.START, "security_check")
                .addEdge("security_check", "emotion_analysis")

                .addConditionalEdges("emotion_analysis",
                        AsyncEdgeAction.edge_async(new EmotionDispatcher()),
                        Map.of("faq_search", "faq_search",
                                "human_takeover", "human_takeover",
                                StateGraph.END, StateGraph.END))

                .addConditionalEdges("faq_search",
                        AsyncEdgeAction.edge_async(new FaqDispatcher()),
                        Map.of("intent_recognition", "intent_recognition",
                                StateGraph.END, StateGraph.END))

                .addConditionalEdges("intent_recognition",
                        AsyncEdgeAction.edge_async(new ConfidenceDispatcher()),
                        Map.of("biz_dispatch", "biz_dispatch",
                                "clarify", "clarify",
                                "llm_chat", "llm_chat",
                                "human_takeover", "human_takeover"))

                .addConditionalEdges("clarify",
                        AsyncEdgeAction.edge_async(new ClarifyDispatcher()),
                        Map.of("intent_recognition", "intent_recognition"))

                .addEdge("biz_dispatch", StateGraph.END)
                .addEdge("llm_chat", StateGraph.END)
                .addEdge("human_takeover", StateGraph.END);

        GraphRepresentation representation = stateGraph.getGraph(
                GraphRepresentation.Type.PLANTUML,
                "Customer Agent Workflow");
        logger.info("\n========== Customer Agent Graph ==========\n{}\n==========================================\n",
                representation.content());

        return stateGraph;
    }

    @Bean
    public CompiledGraph compiledCustomerAgentGraph(StateGraph customerAgentGraph) throws GraphStateException {
        SaverConfig saverConfig = SaverConfig.builder()
                .register(new MemorySaver())
                .build();
        CompiledGraph compiledGraph = customerAgentGraph.compile(
                CompileConfig.builder().saverConfig(saverConfig).build());
        logger.info("CustomerAgent CompiledGraph 创建完成");
        return compiledGraph;
    }
}
