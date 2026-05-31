package com.example.customeragent.graph.dispatcher;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConfidenceDispatcher implements EdgeAction {

    private static final Logger logger = LoggerFactory.getLogger(ConfidenceDispatcher.class);

    @Override
    public String apply(OverAllState state) {
        String intent = state.value("intent").map(Object::toString).orElse("unknown");
        double confidence = state.value("confidence")
                .map(v -> v instanceof Number ? ((Number) v).doubleValue() : 0.0)
                .orElse(0.0);
        int clarifyCount = state.value("clarify_count")
                .map(v -> v instanceof Number ? ((Number) v).intValue() : 0)
                .orElse(0);

        if ("transfer_human".equals(intent) || "complaint".equals(intent)) {
            logger.info("意图为{}，路由到人工接管", intent);
            return "human_takeover";
        }
        if (clarifyCount > 2) {
            logger.info("澄清次数{}超过限制，路由到人工接管", clarifyCount);
            return "human_takeover";
        }
        if (confidence >= 0.8) {
            logger.info("高置信度({})，路由到业务分发", confidence);
            return "biz_dispatch";
        } else if (confidence >= 0.4) {
            logger.info("中置信度({})，路由到澄清确认", confidence);
            return "clarify";
        } else {
            logger.info("低置信度({})，路由到LLM对话", confidence);
            return "llm_chat";
        }
    }
}
