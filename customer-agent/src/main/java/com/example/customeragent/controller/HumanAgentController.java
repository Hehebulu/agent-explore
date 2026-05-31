package com.example.customeragent.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.customeragent.model.HumanQueueItem;
import com.example.customeragent.model.Message;
import com.example.customeragent.model.Session;
import com.example.customeragent.repository.HumanQueueRepository;
import com.example.customeragent.repository.MessageRepository;
import com.example.customeragent.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 人工客服控制台 API
 *
 * 职责：为 agent.html 提供后端接口，支撑人工客服的排队接入和在线对话。
 *
 * 核心接口：
 * - GET  /api/agent/queue             获取排队列表（waiting + serving）
 * - POST /api/agent/queue/{id}/claim  认领排队用户
 * - POST /api/agent/chat/send        客服发送消息
 * - POST /api/agent/session/{id}/resolve  标记会话已解决
 * - GET  /api/agent/session/{id}/messages  轮询新消息（sinceId 增量拉取）
 */
@RestController
@RequestMapping("/api/agent")
public class HumanAgentController {

    private static final Logger logger = LoggerFactory.getLogger(HumanAgentController.class);

    private final HumanQueueRepository humanQueueRepository;
    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;

    public HumanAgentController(HumanQueueRepository humanQueueRepository,
                                SessionRepository sessionRepository,
                                MessageRepository messageRepository) {
        this.humanQueueRepository = humanQueueRepository;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    /**
     * 排队列表
     *
     * 查询状态为 waiting（等待接入）和 serving（服务中）的排队记录。
     * 按优先级降序、创建时间升序排列 —— 高优先级用户排前面。
     * agent.html 每 3 秒轮询此接口刷新左侧队列面板。
     */
    @GetMapping("/queue")
    public List<Map<String, Object>> getQueue() {
        List<HumanQueueItem> items = humanQueueRepository.selectList(
                new LambdaQueryWrapper<HumanQueueItem>()
                        .in(HumanQueueItem::getStatus, "waiting", "serving")
                        .orderByDesc(HumanQueueItem::getPriority)
                        .orderByAsc(HumanQueueItem::getCreatedAt));

        List<Map<String, Object>> result = new ArrayList<>();
        for (HumanQueueItem item : items) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", item.getId());
            map.put("sessionId", item.getSessionId());
            map.put("userId", item.getUserId());
            map.put("priority", item.getPriority());
            map.put("transferReason", item.getTransferReason());
            map.put("status", item.getStatus());
            map.put("createdAt", item.getCreatedAt());
            result.add(map);
        }
        return result;
    }

    /**
     * 认领排队项
     *
     * 客服点击"接入"后：
     * 1. 排队状态 → serving
     * 2. 会话状态 → human_serving（触发 ChatController 走人工分支）
     * 3. 插入一条系统消息通知用户"客服已接入"
     */
    @PostMapping("/queue/{id}/claim")
    public Map<String, Object> claim(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String agentId = body.getOrDefault("agentId", "agent_001");

        HumanQueueItem item = humanQueueRepository.selectById(id);
        if (item == null) {
            return Map.of("success", false, "message", "排队项不存在");
        }
        if (!"waiting".equals(item.getStatus())) {
            return Map.of("success", false, "message", "该用户已被其他客服接入");
        }

        item.setStatus("serving");
        item.setAssignedAt(LocalDateTime.now());
        humanQueueRepository.updateById(item);

        Session session = sessionRepository.selectById(item.getSessionId());
        if (session != null) {
            session.setStatus("human_serving");
            sessionRepository.updateById(session);
        }

        Message sysMsg = Message.builder()
                .sessionId(item.getSessionId())
                .role("system")
                .content("人工客服 " + agentId + " 已接入，为您服务。")
                .nodeName("human_agent")
                .createdAt(LocalDateTime.now())
                .build();
        messageRepository.insert(sysMsg);

        logger.info("客服 {} 认领会话 {}", agentId, item.getSessionId());
        return Map.of("success", true, "sessionId", item.getSessionId(), "message", "已接入");
    }

    /**
     * 客服发送消息给用户
     *
     * 消息以 role=agent 存入消息表，agent.html 和用户端 index.html
     * 各自通过轮询 GET /api/agent/session/{id}/messages 拉取新消息。
     */
    @PostMapping("/chat/send")
    public Map<String, Object> sendMessage(@RequestBody Map<String, String> body) {
        String sessionId = body.get("sessionId");
        String agentId = body.getOrDefault("agentId", "agent_001");
        String content = body.get("content");

        if (sessionId == null || content == null || content.isBlank()) {
            return Map.of("success", false, "message", "参数不完整");
        }

        Session session = sessionRepository.selectById(sessionId);
        if (session == null) {
            return Map.of("success", false, "message", "会话不存在");
        }

        Message msg = Message.builder()
                .sessionId(sessionId)
                .role("agent")
                .content(content)
                .nodeName("human_agent")
                .createdAt(LocalDateTime.now())
                .build();
        messageRepository.insert(msg);

        session.setUpdatedAt(LocalDateTime.now());
        sessionRepository.updateById(session);

        return Map.of("success", true, "messageId", msg.getId());
    }

    /**
     * 标记会话已解决
     *
     * 1. 会话状态 → resolved
     * 2. 排队记录 → resolved
     * 3. 插入结束提示消息，用户端轮询到后停止轮询
     */
    @PostMapping("/session/{id}/resolve")
    public Map<String, String> resolve(@PathVariable String id) {
        Session session = sessionRepository.selectById(id);
        if (session != null) {
            session.setStatus("resolved");
            session.setUpdatedAt(LocalDateTime.now());
            sessionRepository.updateById(session);
        }

        HumanQueueItem item = humanQueueRepository.selectOne(
                new LambdaQueryWrapper<HumanQueueItem>()
                        .eq(HumanQueueItem::getSessionId, id)
                        .eq(HumanQueueItem::getStatus, "serving"));
        if (item != null) {
            item.setStatus("resolved");
            humanQueueRepository.updateById(item);
        }

        Message sysMsg = Message.builder()
                .sessionId(id)
                .role("system")
                .content("本次人工服务已结束，感谢您的咨询。")
                .nodeName("human_agent")
                .createdAt(LocalDateTime.now())
                .build();
        messageRepository.insert(sysMsg);

        logger.info("会话 {} 已标记为已解决", id);
        return Map.of("status", "ok");
    }

    /**
     * 增量拉取会话消息（轮询用）
     *
     * sinceId 参数实现增量拉取：只返回 id > sinceId 的消息。
     * agent.html 每 2 秒调用一次，用户端在人工模式下也调用此接口。
     */
    @GetMapping("/session/{id}/messages")
    public List<Map<String, Object>> getMessages(@PathVariable String id,
                                                  @RequestParam(defaultValue = "0") long sinceId) {
        List<Message> messages = messageRepository.selectList(
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getSessionId, id)
                        .gt(sinceId > 0, Message::getId, sinceId)
                        .orderByAsc(Message::getCreatedAt));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Message msg : messages) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", msg.getId());
            map.put("role", msg.getRole());
            map.put("content", msg.getContent());
            map.put("nodeName", msg.getNodeName());
            map.put("createdAt", msg.getCreatedAt());
            result.add(map);
        }
        return result;
    }
}
