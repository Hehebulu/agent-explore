package com.example.customeragent.service;

import com.example.customeragent.model.dto.EmotionResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/**
 * 情绪分析服务
 *
 * 职责：识别用户消息中的情绪倾向，决定是否需要升级处理。
 *
 * 双阶段检测策略（性能优化）：
 * 1. 关键词先行：如果消息包含"投诉""起诉""欺诈"等升级关键词 → 直接判为 COMPLAINT，跳过 LLM 调用
 * 2. LLM 兜底：关键词未命中时调用 LLM 分析情绪类型和分数
 *
 * 升级条件：
 * - 命中升级关键词（必定升级）
 * - 情绪分数 >= 0.8（如 angry、abuse）
 *
 * 升级后 Graph 工作流将跳过 FAQ 检索，直接路由到人工接管节点。
 */
@Service
public class EmotionAnalysisService {

    /** 触发直接升级的关键词 —— 命中后跳过LLM，直接标记为需要人工 */
    private static final Set<String> ESCALATION_KEYWORDS = Set.of(
            "投诉", "起诉", "工商局", "消协", "退钱", "欺诈", "骗子", "举报"
    );
    /** 情绪分数超过此阈值时触发升级 */
    private static final BigDecimal ESCALATION_THRESHOLD = new BigDecimal("0.8");

    private final ChatClient chatClient;

    public EmotionAnalysisService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * 分析用户消息情绪
     *
     * @param userMessage 用户原始消息
     * @return EmotionResult 包含情绪类型、分数、是否升级及原因
     */
    public EmotionResult analyze(String userMessage) {
        // 第一阶段：关键词快速路径
        for (String keyword : ESCALATION_KEYWORDS) {
            if (userMessage.contains(keyword)) {
                return EmotionResult.builder()
                        .emotionType("COMPLAINT")
                        .emotionScore(new BigDecimal("0.95"))
                        .escalated(true)
                        .escalationReason("命中升级关键词: " + keyword)
                        .build();
            }
        }

        // 第二阶段：LLM 情绪分析
        String prompt = """
                分析以下用户消息的情绪，返回JSON格式：{"type":"normal|unhappy|angry|complaint|abuse","score":0.0~1.0}
                只返回JSON，不要其他内容。

                用户消息：%s
                """.formatted(userMessage);

        try {
            String response = chatClient.prompt().user(prompt).call().content();
            String type = extractJsonField(response, "type", "normal");
            double score = Double.parseDouble(extractJsonField(response, "score", "0.2"));
            BigDecimal scoreBd = BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP);
            boolean escalated = scoreBd.compareTo(ESCALATION_THRESHOLD) >= 0;

            return EmotionResult.builder()
                    .emotionType(type.toUpperCase())
                    .emotionScore(scoreBd)
                    .escalated(escalated)
                    .escalationReason(escalated ? "情绪分数超阈值: " + scoreBd : null)
                    .build();
        } catch (Exception e) {
            // LLM 调用失败时降级为正常情绪，不阻塞主流程
            return EmotionResult.builder()
                    .emotionType("NORMAL")
                    .emotionScore(new BigDecimal("0.20"))
                    .escalated(false)
                    .build();
        }
    }

    /** 从 LLM 返回的 JSON 中提取字段值（简易解析，避免引入 Jackson 依赖） */
    private String extractJsonField(String json, String field, String defaultVal) {
        String key = "\"" + field + "\"";
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) return defaultVal;
        int colonIdx = json.indexOf(":", keyIdx);
        if (colonIdx < 0) return defaultVal;
        int valStart = json.indexOf("\"", colonIdx);
        // 非字符串值（如数字），直接取到逗号或右括号
        if (valStart < 0 || valStart > colonIdx + 3) {
            int commaIdx = json.indexOf(",", colonIdx);
            int braceIdx = json.indexOf("}", colonIdx);
            int endIdx = commaIdx > 0 ? Math.min(commaIdx, braceIdx) : braceIdx;
            if (endIdx < 0) endIdx = json.length();
            return json.substring(colonIdx + 1, endIdx).trim();
        }
        // 字符串值
        int valEnd = json.indexOf("\"", valStart + 1);
        if (valEnd < 0) return defaultVal;
        return json.substring(valStart + 1, valEnd);
    }
}
