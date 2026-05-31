package com.example.customeragent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.example.customeragent.model.dto.FaqMatchResult;
import com.example.customeragent.service.FaqService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class FaqNode implements AsyncNodeActionWithConfig {

    private static final Logger logger = LoggerFactory.getLogger(FaqNode.class);
    private final FaqService faqService;

    public FaqNode(FaqService faqService) {
        this.faqService = faqService;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state, RunnableConfig config) {
        String userMessage = state.value("user_message", "");
        logger.info("FAQ检索: {}", userMessage);

        FaqMatchResult result = faqService.search(userMessage);
        logger.info("FAQ结果: matched={}, similarity={}", result.isMatched(), result.getSimilarity());

        if (result.isMatched()) {
            return CompletableFuture.completedFuture(Map.of(
                    "faq_matched", true,
                    "faq_question", result.getQuestion(),
                    "faq_answer", result.getAnswer(),
                    "faq_similarity", result.getSimilarity(),
                    "ai_response", result.getAnswer()
            ));
        }
        return CompletableFuture.completedFuture(Map.of(
                "faq_matched", false,
                "faq_similarity", result.getSimilarity()
        ));
    }
}
