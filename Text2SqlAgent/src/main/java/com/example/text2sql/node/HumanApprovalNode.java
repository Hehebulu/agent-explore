package com.example.text2sql.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.action.InterruptableAction;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 人工审批节点 — Human-in-the-Loop 核心
 *
 * 实现 InterruptableAction 接口：
 * - interrupt(): 在执行前被调用，决定是否中断等待人工输入
 * - apply(): 执行节点逻辑（审批通过后的处理）
 *
 * 支持三种人工操作：
 * - APPROVE: 批准执行原始 SQL
 * - REJECT: 拒绝执行，结束工作流
 * - MODIFY: 使用人工修改后的 SQL 继续执行
 *
 * interrupt/resume 机制：
 * 1. Graph 到达此节点时，先调用 interrupt()
 * 2. 如果返回 InterruptionMetadata，Graph 暂停并保存 checkpoint
 * 3. 人工通过 /resume 接口提交决策
 * 4. Graph 从 checkpoint 恢复，再次调用 interrupt() -> 检测到 HUMAN_FEEDBACK_METADATA_KEY -> 继续执行 apply()
 */
public class HumanApprovalNode implements AsyncNodeActionWithConfig, InterruptableAction {

    private static final Logger logger = LoggerFactory.getLogger(HumanApprovalNode.class);

    @Override
    public Optional<InterruptionMetadata> interrupt(String nodeId, OverAllState state, RunnableConfig config) {
        logger.info("=== HumanApprovalNode.interrupt() — nodeId: {} ===", nodeId);

        // Controller 通过 updateState 将 human_action 写入 checkpoint 后再 resume
        // → state 中 human_action 存在且非空 → 说明是 resume，跳过中断，直接执行 apply()
        Optional<Object> humanAction = state.value("human_action");
        if (humanAction.isPresent()) {
            String action = humanAction.get().toString();
            if (!action.isEmpty()) {
                logger.info("检测到 human_action={}, resume 场景，跳过中断", action);
                return Optional.empty();
            }
        }

        // 获取当前状态信息
        String userQuestion = state.value("user_question").orElse("").toString();
        String generatedSql = state.value("generated_sql").orElse("").toString();
        String approvalCycleId = state.value("approval_cycle_id").orElse("").toString();
        String checkResult = state.value("sql_check_result").orElse("").toString();
        String checkSuggestion = state.value("sql_check_suggestion").orElse("").toString();
        String riskLevel = state.value("risk_level").orElse("MEDIUM").toString();
        Object sqlValid = state.value("sql_valid").orElse(null);

        @SuppressWarnings("unchecked")
        List<String> tablesUsed = (List<String>) state.value("tables_used").orElse(new ArrayList<>());

        // 构建中断元数据 — 用于通知前端
        logger.info("SQL 需要人工审批: tables={}, cycleId={}, risk={}, valid={}",
                tablesUsed, approvalCycleId, riskLevel, sqlValid);

        InterruptionMetadata interruptionMetadata = InterruptionMetadata.builder(nodeId, state)
                .addMetadata("type", "SQL_APPROVAL")
                .addMetadata("user_question", userQuestion)
                .addMetadata("generated_sql", generatedSql)
                .addMetadata("tables_used", tablesUsed)
                .addMetadata("approval_cycle_id", approvalCycleId)
                .addMetadata("interrupt_time", System.currentTimeMillis())
                .addMetadata("session_id", state.value("session_id").orElse("").toString())
                .addMetadata("node_name", "human_approval")
                .addMetadata("node", nodeId)
                .addMetadata("message", "SQL 已生成，等待人工审批后才能执行")
                .addMetadata("available_actions", List.of("APPROVE", "REJECT", "MODIFY"))
                .addMetadata("check_result", checkResult)
                .addMetadata("check_suggestion", checkSuggestion)
                .addMetadata("risk_level", riskLevel)
                .addMetadata("sql_valid", sqlValid != null ? sqlValid.toString() : "")
                .build();

        return Optional.of(interruptionMetadata);
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state, RunnableConfig config) {
        logger.info("=== HumanApprovalNode.apply() — 处理人工审批结果 ===");

        // 从 state 读取 — Controller 已通过 updateState 写入 checkpoint
        String humanAction = state.value("human_action").orElse("APPROVE").toString();
        String modifiedSql = state.value("modified_sql").orElse("").toString();
        String humanComment = state.value("human_comment").orElse("").toString();
        logger.info("从 State 读取人工决策: action={}", humanAction);


        Map<String, Object> result = new HashMap<>();

        switch (humanAction.toUpperCase()) {
            case "APPROVE" -> {
                logger.info("人工审批通过，继续执行 SQL");
                result.put("human_action", "APPROVE");
                result.put("workflow_status", "APPROVED");
                result.put("executed_sql", state.value("generated_sql").orElse("").toString());
                result.put("next_node", "execute_sql");
            }

            case "MODIFY" -> {
                logger.info("人工修改了 SQL: {}", modifiedSql);
                if (modifiedSql != null && !modifiedSql.isBlank()) {
                    result.put("executed_sql", modifiedSql);
                    result.put("modified_sql", modifiedSql);
                    result.put("human_action", "MODIFY");
                    result.put("workflow_status", "APPROVED");
                    result.put("next_node", "execute_sql");
                } else {
                    logger.warn("MODIFY 操作但未提供修改后的 SQL，使用原始 SQL");
                    result.put("human_action", "MODIFY");
                    result.put("workflow_status", "APPROVED");
                    result.put("executed_sql", state.value("generated_sql").orElse("").toString());
                    result.put("next_node", "execute_sql");
                }
            }

            case "REJECT" -> {
                logger.info("人工审批拒绝，返回重新生成 SQL");
                result.put("human_action", "REJECT");
                result.put("workflow_status", "REGENERATING");
                result.put("error_message", "SQL 执行被人为拒绝，将重新生成");
                result.put("next_node", "generate_sql");
            }

            case "", "PENDING" -> {
                // GenerateSqlNode 清空 human_action 后，HUMAN_FEEDBACK_METADATA_KEY 已被移除，
                // interrupt() 会正常触发中断，理论上不会走到这里。
                // 如果因框架行为变化到达此处，self-loop 让 interrupt() 下次被调用时正常中断。
                logger.info("human_action 为空，新审批周期，等待 interrupt() 触发中断");
                result.put("workflow_status", "WAITING_APPROVAL");
                result.put("next_node", "human_approval");
            }
            default -> {
                logger.warn("未知的人工动作: {}, 默认拒绝", humanAction);
                result.put("human_action", "REJECT");
                result.put("workflow_status", "REJECTED");
                result.put("next_node", "END");
            }
        }

        result.put("human_comment", humanComment);
        result.put("current_node", "human_approval");

        return CompletableFuture.completedFuture(result);
    }

}
