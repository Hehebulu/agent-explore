package com.example.customeragent.graph.dispatcher;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EmotionDispatcher implements EdgeAction {

    private static final Logger logger = LoggerFactory.getLogger(EmotionDispatcher.class);

    @Override
    public String apply(OverAllState state) {
        boolean escalated = state.value("need_human").map(v -> (Boolean) v).orElse(false);
        boolean securityBlocked = state.value("security_blocked").map(v -> (Boolean) v).orElse(false);

        if (securityBlocked) {
            logger.info("安全检查拦截，路由到 END");
            return StateGraph.END;
        }
        if (escalated) {
            logger.info("情绪升级，路由到人工接管");
            return "human_takeover";
        }
        return "faq_search";
    }
}
