package com.example.customeragent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class LlmChatNode implements AsyncNodeActionWithConfig {

    private static final Logger logger = LoggerFactory.getLogger(LlmChatNode.class);
    private final ChatClient chatClient;

    public LlmChatNode(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state, RunnableConfig config) {
        String userMessage = state.value("user_message", "");
        logger.info("LLM闲聊模式: {}", userMessage);

        String prompt = """
                你是电商平台的AI客服助手"小智"。请用友好、专业的语气回复用户。
                如果用户是闲聊，保持轻松愉快的语气。
                如果用户的问题不明确，请耐心引导。

                用户消息：%s
                """.formatted(userMessage);

        try {
            String response = chatClient.prompt().user(prompt).call().content();
            logger.info("LLM回复: {}", response);
            return CompletableFuture.completedFuture(Map.of("ai_response", response));
        } catch (Exception e) {
            logger.error("LLM调用失败", e);
            return CompletableFuture.completedFuture(Map.of(
                    "ai_response", "抱歉，我暂时无法处理您的问题，正在为您转接人工客服..."
            ));
        }
    }
}
