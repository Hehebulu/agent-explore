package com.example.customeragent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class ClarifyNode implements AsyncNodeActionWithConfig {

    private static final Logger logger = LoggerFactory.getLogger(ClarifyNode.class);

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state, RunnableConfig config) {
        String question = state.value("clarification_question").map(Object::toString).orElse("");
        int clarifyCount = state.value("clarify_count")
                .map(v -> v instanceof Number ? ((Number) v).intValue() : 0)
                .orElse(0) + 1;

        logger.info("澄清确认: round={}, question={}", clarifyCount, question);
        return CompletableFuture.completedFuture(Map.of(
                "ai_response", question,
                "clarify_count", clarifyCount,
                "need_clarify", true
        ));
    }
}
