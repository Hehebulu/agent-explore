package com.example.text2sql.dispatcher;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 人工审批后的路由分发器
 *
 * 根据 next_node 决定下一步:
 * - execute_sql (来自 APPROVE/MODIFY) → execute_sql
 * - generate_sql (来自 REJECT) → generate_sql
 * - 其他 → 默认 END
 */
public class ApprovalDispatcher implements EdgeAction {

    private static final Logger logger = LoggerFactory.getLogger(ApprovalDispatcher.class);

    @Override
    public String apply(OverAllState state) throws Exception {
        String humanAction = state.value("human_action").orElse("").toString();
        String nextNode = state.value("next_node").orElse("").toString();

        logger.info("ApprovalDispatcher: human_action={}, next_node={}", humanAction, nextNode);

        // 显式 END（来自 HumanApprovalNode 未知 action 的 default 分支）
        if (StateGraph.END.equals(nextNode) || "END".equals(nextNode)) {
            logger.info("审批结果要求结束工作流");
            return StateGraph.END;
        }

        if ("execute_sql".equals(nextNode)) {
            return "execute_sql";
        }
        if ("generate_sql".equals(nextNode)) {
            return "generate_sql";
        }

        // 如果 next_node 因状态合并异常而丢失，
        // 回到 human_approval 重新中断，避免工作流直接 END
        logger.warn("next_node 丢失 (human_action={}), 回到 human_approval 重新中断", humanAction);
        return "human_approval";
    }
}
