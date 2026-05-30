package com.example.text2sql.dispatcher;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQL 校验后的路由分发器
 *
 * 所有 SQL（不论校验结果和风险等级）统一进入人工审批，
 * 确保前端通过 HumanApprovalNode.interrupt() 收到通知。
 * 用户可在审批面板看到风险等级和校验详情后再决定。
 */
public class SqlCheckDispatcher implements EdgeAction {

    private static final Logger logger = LoggerFactory.getLogger(SqlCheckDispatcher.class);

    @Override
    public String apply(OverAllState state) throws Exception {
        Boolean sqlValid = state.value("sql_valid", false);
        String riskLevel = state.value("risk_level", "MEDIUM");

        logger.info("SqlCheckDispatcher: valid={}, risk={} → human_approval", sqlValid, riskLevel);
        return "human_approval";
    }
}
