package com.example.customeragent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.example.customeragent.service.SecurityCheckService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class SecurityCheckNode implements AsyncNodeActionWithConfig {

    private static final Logger logger = LoggerFactory.getLogger(SecurityCheckNode.class);
    private final SecurityCheckService securityCheckService;

    public SecurityCheckNode(SecurityCheckService securityCheckService) {
        this.securityCheckService = securityCheckService;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state, RunnableConfig config) {
        String userMessage = state.value("user_message", "");
        logger.info("安全检查: {}", userMessage);

        boolean blocked = securityCheckService.containsSensitiveWord(userMessage);
        if (blocked) {
            String hitWord = securityCheckService.findHitWord(userMessage);
            logger.warn("敏感词命中: {}", hitWord);
            return CompletableFuture.completedFuture(Map.of(
                    "security_blocked", true,
                    "blocked_reason", "命中敏感词: " + hitWord,
                    "ai_response", "抱歉，您的内容不符合平台规范。"
            ));
        }
        return CompletableFuture.completedFuture(Map.of("security_blocked", false));
    }
}
