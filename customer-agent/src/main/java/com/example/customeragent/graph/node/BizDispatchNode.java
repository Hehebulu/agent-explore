package com.example.customeragent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class BizDispatchNode implements AsyncNodeActionWithConfig {

    private static final Logger logger = LoggerFactory.getLogger(BizDispatchNode.class);
    private final ChatClient chatClient;

    public BizDispatchNode(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state, RunnableConfig config) {
        String intent = state.value("intent").map(Object::toString).orElse("unknown");
        String userMessage = state.value("user_message", "");
        logger.info("业务分发: intent={}", intent);

        String response = generateBizResponse(intent, userMessage);
        return CompletableFuture.completedFuture(Map.of("ai_response", response, "biz_result", response));
    }

    private String generateBizResponse(String intent, String userMessage) {
        String prompt = """
                你是电商客服，请根据用户意图生成回复。
                意图：%s
                用户消息：%s
                要求：简短专业，50字以内。
                """.formatted(intent, userMessage);

        try {
            return chatClient.prompt().user(prompt).call().content();
        } catch (Exception e) {
            return switch (intent) {
                case "query_product" -> "正在为您查询商品信息，请稍候...";
                case "query_order" -> "正在为您查询订单信息，请稍候...";
                case "modify_order" -> "请提供您的订单号，我将协助您修改订单。";
                case "cancel_order" -> "请提供您的订单号，我将协助您取消订单。";
                case "refund_order" -> "很抱歉，请提供订单号，我将为您处理退款。";
                case "complaint" -> "非常抱歉给您带来不好的体验，我已记录您的问题，会立即处理。";
                default -> "收到您的消息，正在为您处理...";
            };
        }
    }
}
