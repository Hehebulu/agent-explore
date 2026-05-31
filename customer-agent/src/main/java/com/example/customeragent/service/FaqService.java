package com.example.customeragent.service;

import com.example.customeragent.model.dto.FaqMatchResult;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.stereotype.Service;

/**
 * FAQ 向量检索服务
 *
 * 职责：将用户问题转为向量，在 FAQ 知识库中做语义相似度检索。
 * 如果匹配到高相似度（>=0.9）的标准问答对，直接返回预设答案，跳过 LLM 调用环节。
 *
 * 技术链路：
 * 用户消息 → Embedding 向量化 → PGVector 余弦相似度检索 → 返回 Top-1 结果
 *
 * 注意：Spring AI 1.1.0 的 Document.getScore() 可能不存在，
 * 使用反射调用，失败时回退为 1.0（假设命中即匹配）。
 */
@Service
public class FaqService {

    /** 相似度阈值：只有 >= 0.9 才视为命中 */
    private static final double SIMILARITY_THRESHOLD = 0.9;
    private final VectorStore vectorStore;

    public FaqService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 向量相似度搜索
     *
     * @param query 用户原始问题
     * @return FaqMatchResult，matched=true 表示命中且相似度达标
     */
    public FaqMatchResult search(String query) {
        try {
            var results = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(1)
                            .similarityThreshold(SIMILARITY_THRESHOLD)
                            .build()
            );

            if (results.isEmpty()) {
                return FaqMatchResult.builder().matched(false).build();
            }

            var doc = results.get(0);
            double similarity = 0.0;
            try {
                // Spring AI 1.1.0 中 Document 的 getScore 方法可能通过反射获取
                var scoreField = doc.getClass().getMethod("getScore");
                Object score = scoreField.invoke(doc);
                if (score instanceof Double d) similarity = d;
                else if (score instanceof Float f) similarity = f.doubleValue();
            } catch (Exception e) {
                similarity = 1.0; // 反射失败时假设命中
            }

            if (similarity < SIMILARITY_THRESHOLD) {
                return FaqMatchResult.builder().matched(false).similarity(similarity).build();
            }

            return FaqMatchResult.builder()
                    .matched(true)
                    .question(doc.getMetadata() != null ? (String) doc.getMetadata().getOrDefault("question", "") : "")
                    .answer(doc.getText())
                    .similarity(similarity)
                    .build();
        } catch (Exception e) {
            return FaqMatchResult.builder().matched(false).build();
        }
    }
}
