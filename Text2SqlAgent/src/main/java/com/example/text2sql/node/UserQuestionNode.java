package com.example.text2sql.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.example.text2sql.agent.SqlAgentPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 用户问题分析节点
 *
 * 功能：
 * - 接收用户自然语言问题
 * - 分析意图、识别可能涉及的实体
 * - 判断是否需要澄清
 * - 输出分析结果到 State
 *
 * 输入: user_question, chat_history
 * 输出: intent, entities, aggregations, filters, needs_clarification
 */
public class UserQuestionNode implements AsyncNodeActionWithConfig {

    private static final Logger logger = LoggerFactory.getLogger(UserQuestionNode.class);
    private final ChatClient chatClient;

    public UserQuestionNode(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state, RunnableConfig config) {
        logger.info("=== UserQuestionNode: 开始分析用户问题 ===");

        String userQuestion = state.value("user_question", "");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> chatHistory = state.value("chat_history", new ArrayList<>());

        if (userQuestion.isEmpty()) {
            logger.warn("用户问题为空");
            return CompletableFuture.completedFuture(Map.of(
                    "workflow_status", "FAILED",
                    "error_message", "用户问题为空"
            ));
        }

        String prompt = SqlAgentPrompt.QUESTION_ANALYSIS_PROMPT
                .replace("{user_question}", userQuestion)
                .replace("{chat_history}", chatHistory.toString());

        try {
            String response = this.chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            logger.info("UserQuestionNode 分析完成: {}", response);

            Map<String, Object> result = new HashMap<>();
            result.put("intent", response);
            result.put("workflow_status", "ANALYZING");
            result.put("current_node", "user_question");

            return CompletableFuture.completedFuture(result);

        } catch (Exception e) {
            logger.error("UserQuestionNode 分析失败", e);
            return CompletableFuture.completedFuture(Map.of(
                    "workflow_status", "FAILED",
                    "error_message", "问题分析失败: " + e.getMessage()
            ));
        }
    }
}
