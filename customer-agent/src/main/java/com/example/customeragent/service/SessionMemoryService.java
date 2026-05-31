package com.example.customeragent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.customeragent.model.Message;
import com.example.customeragent.model.Session;
import com.example.customeragent.model.dto.SessionSummary;
import com.example.customeragent.repository.MessageRepository;
import com.example.customeragent.repository.SessionRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 会话记忆服务
 *
 * 职责：管理对话上下文，为 LLM 提供历史消息。
 *
 * 关键设计决策：
 * - 上下文窗口限制为最近 10 条消息（防止 token 超限和成本失控）
 * - 历史消息按创建时间升序排列，保持对话时序
 * - buildSessionSummary 聚合会话元数据 + 上下文文本，供前端和管理后台使用
 */
@Service
public class SessionMemoryService {

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;

    public SessionMemoryService(SessionRepository sessionRepository,
                                 MessageRepository messageRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    /**
     * 获取最近 N 条消息
     *
     * 如果消息总数超过 limit，截取末尾 limit 条（最新的对话）。
     * 这保证了 LLM 上下文不会无限增长。
     */
    public List<Message> getRecentMessages(String sessionId, int limit) {
        List<Message> messages = messageRepository.selectList(
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getSessionId, sessionId)
                        .orderByAsc(Message::getCreatedAt));
        int size = messages.size();
        if (size <= limit) return messages;
        return messages.subList(size - limit, size);
    }

    /**
     * 构建对话上下文文本
     *
     * 输出格式：每行一条 "[role]: content"
     * 用于注入 LLM prompt 的对话历史部分。
     */
    public String buildContextSummary(String sessionId) {
        List<Message> messages = getRecentMessages(sessionId, 10);
        if (messages.isEmpty()) return "";
        return messages.stream()
                .map(m -> "[" + m.getRole() + "]: " + m.getContent())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 构建会话摘要（前端展示用）
     *
     * 包含：会话基本信息 + 当前情绪/意图 + 负面轮次 + 上下文文本
     */
    public SessionSummary buildSessionSummary(String sessionId) {
        Session session = sessionRepository.selectById(sessionId);
        if (session == null) return null;

        String context = buildContextSummary(sessionId);

        return SessionSummary.builder()
                .sessionId(session.getId())
                .userId(session.getUserId())
                .channel(session.getChannel())
                .emotionState(session.getEmotionState())
                .currentIntent(session.getCurrentIntent())
                .negativeRounds(session.getNegativeRounds())
                .status(session.getStatus())
                .aiSummary(context)
                .createdAt(session.getCreatedAt())
                .build();
    }
}
