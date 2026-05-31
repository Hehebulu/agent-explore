package com.example.customeragent.service;

import com.example.customeragent.model.dto.IntentResult;
import com.example.customeragent.model.Message;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 意图识别服务
 *
 * 职责：分析用户消息的业务意图，输出意图分类 + 置信度 + 可选追问。
 *
 * 支持 11 种意图类型：
 * query_product / compare_product / query_inventory / query_order /
 * modify_order / cancel_order / refund_order / transfer_human /
 * chit_chat / complaint / unknown
 *
 * 特殊处理：
 * - "转人工""找客服""找真人" → 直接返回 transfer_human / 0.99（跳过 LLM）
 * - 有对话历史时，将历史上下文注入 prompt，提高意图判别的准确率
 *
 * 置信度路由（由下游 ConfidenceDispatcher 决定）：
 * - >= 0.8（HIGH）→ 业务分发，直接回答
 * - 0.4~0.8（MEDIUM）→ 发起澄清追问
 * - < 0.4（LOW）→ 进入自由 LLM 聊天模式
 */
@Service
public class IntentRecognitionService {

    private static final String INTENT_PROMPT = """
            你是电商客服意图识别专家。分析用户消息，返回JSON：
            {
              "intent": "query_product|compare_product|query_inventory|query_order|modify_order|cancel_order|refund_order|transfer_human|chit_chat|complaint|unknown",
              "confidence": 0.0~1.0,
              "clarification": "如果置信度中等，可以简短追问一句（中文），高/低置信度可为null"
            }
            只返回JSON，不要其他内容。

            用户消息：%s
            """;

    private final ChatClient chatClient;

    public IntentRecognitionService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * 识别用户意图
     *
     * @param userMessage 当前用户消息
     * @param history     最近 N 条对话历史，用于上下文感知
     * @return IntentResult 意图分类 + 置信度 + 可选追问
     */
    public IntentResult recognize(String userMessage, List<Message> history) {
        // 快速路径：明确要求转人工的关键词
        String lowerMsg = userMessage.toLowerCase().trim();
        if (lowerMsg.contains("转人工") || lowerMsg.contains("找客服") || lowerMsg.contains("找真人")) {
            return IntentResult.builder().intent("transfer_human").confidence(0.99).build();
        }

        // 构建 prompt：有历史时附加上下文，提高长对话中的意图理解准确率
        String prompt;
        if (history != null && history.size() > 1) {
            StringBuilder context = new StringBuilder();
            for (var msg : history) {
                if (msg.getCreatedAt() != null) {
                    context.append("[").append(msg.getRole()).append("]: ").append(msg.getContent()).append("\n");
                }
            }
            prompt = """
                    你是电商客服意图识别专家。分析用户消息，返回JSON。

                    对话历史：
                    %s

                    当前用户消息：%s

                    {"intent":"query_product|compare_product|query_inventory|query_order|modify_order|cancel_order|refund_order|transfer_human|chit_chat|complaint|unknown","confidence":0.0~1.0,"clarification":"如果置信度0.4~0.8可简短追问一句，否则null"}
                    只返回JSON。
                    """.formatted(context.toString(), userMessage);
        } else {
            prompt = INTENT_PROMPT.formatted(userMessage);
        }

        try {
            String response = chatClient.prompt().user(prompt).call().content();
            String intent = extractJsonField(response, "intent", "unknown");
            double confidence = Double.parseDouble(extractJsonField(response, "confidence", "0.3"));
            String clarification = extractJsonNullableField(response, "clarification");

            return IntentResult.builder()
                    .intent(intent)
                    .confidence(confidence)
                    .clarification(clarification)
                    .build();
        } catch (Exception e) {
            // LLM 调用失败时返回最低置信度的 unknown，由下游兜底处理
            return IntentResult.builder().intent("unknown").confidence(0.1).build();
        }
    }

    /** 从 LLM 返回的 JSON 中提取字符串字段值 */
    private String extractJsonField(String json, String field, String defaultVal) {
        String key = "\"" + field + "\"";
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) return defaultVal;
        int colonIdx = json.indexOf(":", keyIdx);
        if (colonIdx < 0) return defaultVal;
        int valStart = json.indexOf("\"", colonIdx);
        if (valStart < 0 || valStart > colonIdx + 3) {
            int commaIdx = json.indexOf(",", colonIdx);
            int braceIdx = json.indexOf("}", colonIdx);
            int endIdx = commaIdx > 0 ? Math.min(commaIdx, braceIdx) : braceIdx;
            if (endIdx < 0) endIdx = json.length();
            return json.substring(colonIdx + 1, endIdx).trim();
        }
        int valEnd = json.indexOf("\"", valStart + 1);
        if (valEnd < 0) return defaultVal;
        return json.substring(valStart + 1, valEnd);
    }

    /** 提取可空字段（null 或空字符串均返回 null） */
    private String extractJsonNullableField(String json, String field) {
        String val = extractJsonField(json, field, "");
        return val.isEmpty() || "null".equals(val) ? null : val;
    }
}
