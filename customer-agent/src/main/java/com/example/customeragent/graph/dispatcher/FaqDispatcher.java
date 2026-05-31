package com.example.customeragent.graph.dispatcher;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.EdgeAction;

public class FaqDispatcher implements EdgeAction {

    @Override
    public String apply(OverAllState state) {
        boolean faqMatched = state.value("faq_matched").map(v -> (Boolean) v).orElse(false);
        if (faqMatched) {
            return StateGraph.END;
        }
        return "intent_recognition";
    }
}
