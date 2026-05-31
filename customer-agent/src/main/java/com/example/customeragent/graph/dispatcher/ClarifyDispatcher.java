package com.example.customeragent.graph.dispatcher;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.EdgeAction;

public class ClarifyDispatcher implements EdgeAction {

    @Override
    public String apply(OverAllState state) {
        return "intent_recognition";
    }
}
