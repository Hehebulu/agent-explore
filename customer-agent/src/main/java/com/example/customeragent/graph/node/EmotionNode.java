package com.example.customeragent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.example.customeragent.model.dto.EmotionResult;
import com.example.customeragent.service.EmotionAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class EmotionNode implements AsyncNodeActionWithConfig {

    private static final Logger logger = LoggerFactory.getLogger(EmotionNode.class);
    private final EmotionAnalysisService emotionAnalysisService;

    public EmotionNode(EmotionAnalysisService emotionAnalysisService) {
        this.emotionAnalysisService = emotionAnalysisService;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state, RunnableConfig config) {
        String userMessage = state.value("user_message", "");
        logger.info("情绪分析: {}", userMessage);

        EmotionResult result = emotionAnalysisService.analyze(userMessage);
        logger.info("情绪结果: type={}, score={}, escalated={}",
                result.getEmotionType(), result.getEmotionScore(), result.isEscalated());

        return CompletableFuture.completedFuture(Map.of(
                "emotion_type", result.getEmotionType(),
                "emotion_score", result.getEmotionScore(),
                "need_human", result.isEscalated(),
                "transfer_reason", result.getEscalationReason() != null ? result.getEscalationReason() : ""
        ));
    }
}
