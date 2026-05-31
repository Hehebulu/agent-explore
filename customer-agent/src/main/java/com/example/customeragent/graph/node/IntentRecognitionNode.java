package com.example.customeragent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.example.customeragent.model.dto.IntentResult;
import com.example.customeragent.service.IntentRecognitionService;
import com.example.customeragent.service.SessionMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class IntentRecognitionNode implements AsyncNodeActionWithConfig {

    private static final Logger logger = LoggerFactory.getLogger(IntentRecognitionNode.class);
    private final IntentRecognitionService intentRecognitionService;
    private final SessionMemoryService sessionMemoryService;

    public IntentRecognitionNode(IntentRecognitionService intentRecognitionService,
                                  SessionMemoryService sessionMemoryService) {
        this.intentRecognitionService = intentRecognitionService;
        this.sessionMemoryService = sessionMemoryService;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state, RunnableConfig config) {
        String userMessage = state.value("user_message", "");
        String sessionId = state.value("session_id", "");
        String clarification = state.value("clarification").map(Object::toString).orElse("");

        String effectiveMessage = clarification.isEmpty() ? userMessage : clarification;
        if (!clarification.isEmpty()) {
            logger.info("带澄清上下文重新识别: {}", effectiveMessage);
        } else {
            logger.info("意图识别: {}", effectiveMessage);
        }

        var history = sessionMemoryService.getRecentMessages(sessionId, 10);
        IntentResult result = intentRecognitionService.recognize(effectiveMessage, history);
        logger.info("意图结果: intent={}, confidence={}", result.getIntent(), result.getConfidence());

        int clarifyCount = state.value("clarify_count")
                .map(v -> v instanceof Number ? ((Number) v).intValue() : 0)
                .orElse(0);

        return CompletableFuture.completedFuture(Map.of(
                "intent", result.getIntent(),
                "confidence", result.getConfidence(),
                "clarification_question", result.getClarification() != null ? result.getClarification() : "",
                "clarify_count", clarifyCount
        ));
    }
}
