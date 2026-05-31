package com.example.customeragent.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.customeragent.model.HumanQueueItem;
import com.example.customeragent.model.Message;
import com.example.customeragent.model.Session;
import com.example.customeragent.model.dto.ChatRequest;
import com.example.customeragent.model.dto.SessionSummary;
import com.example.customeragent.repository.HumanQueueRepository;
import com.example.customeragent.repository.MessageRepository;
import com.example.customeragent.repository.SessionRepository;
import com.example.customeragent.service.CustomerAgentService;
import com.example.customeragent.service.SessionMemoryService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 用户端 API 控制器
 *
 * 职责：接收用户消息、管理会话生命周期、处理人工转接请求。
 * 核心接口：
 * - POST /api/chat/send    用户发送消息（SSE 流式响应）
 * - POST /api/session      创建新会话
 * - GET  /api/session/{id} 获取会话摘要
 * - POST /api/session/{id}/transfer-human  手动转人工
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    private final CustomerAgentService customerAgentService;
    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final SessionMemoryService sessionMemoryService;
    private final HumanQueueRepository humanQueueRepository;

    public ChatController(CustomerAgentService customerAgentService,
                          SessionRepository sessionRepository,
                          MessageRepository messageRepository,
                          SessionMemoryService sessionMemoryService,
                          HumanQueueRepository humanQueueRepository) {
        this.customerAgentService = customerAgentService;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.sessionMemoryService = sessionMemoryService;
        this.humanQueueRepository = humanQueueRepository;
    }

    /**
     * 用户发送消息（核心入口）
     *
     * 流程分支：
     * 1. 人工服务中（human_serving）→ 直接存消息入库，跳过 AI Graph，等待客服回复
     * 2. 正常模式 → 交给 CustomerAgentService 驱动 Graph 工作流，SSE 流式返回结果
     *
     * @return SSE 事件流，前端逐事件渲染
     */
    @PostMapping(value = "/chat/send", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> sendMessage(@Valid @RequestBody ChatRequest request) {
        String sessionId = request.getSessionId() != null ? request.getSessionId()
                : UUID.randomUUID().toString().substring(0, 8);
        String channel = request.getChannel() != null ? request.getChannel() : "web";

        logger.info("收到消息: sessionId={}, userId={}, content={}", sessionId, request.getUserId(), request.getContent());

        // 【分支1】人工服务模式下直接存储消息，不走AI Graph
        Session session = sessionRepository.selectById(sessionId);
        if (session != null && "human_serving".equals(session.getStatus())) {
            Message userMsg = Message.builder()
                    .sessionId(sessionId)
                    .role("user")
                    .content(request.getContent())
                    .nodeName("human_chat")
                    .createdAt(LocalDateTime.now())
                    .build();
            messageRepository.insert(userMsg);
            session.setUpdatedAt(LocalDateTime.now());
            sessionRepository.updateById(session);

            Map<String, Object> ack = Map.of("type", "message", "content", "消息已发送", "role", "user");
            return Flux.just(ServerSentEvent.<Map<String, Object>>builder()
                    .event("message")
                    .data(ack)
                    .build());
        }

        // 【分支2】正常 AI 模式：驱动 Graph 工作流
        return customerAgentService.processMessage(sessionId, request.getUserId(), request.getContent(), channel)
                .map(eventData -> ServerSentEvent.<Map<String, Object>>builder()
                        .event(eventData.getOrDefault("type", "message").toString())
                        .data(eventData)
                        .build())
                .doOnError(e -> logger.error("SSE流异常: {}", e.getMessage()));
    }

    /** 创建新会话，返回 sessionId */
    @PostMapping("/session")
    public Map<String, String> createSession(@RequestBody Map<String, String> body) {
        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        String userId = body.getOrDefault("userId", "anonymous");
        Session session = Session.builder().id(sessionId).userId(userId).channel("web").status("active").build();
        sessionRepository.insert(session);
        return Map.of("sessionId", sessionId, "userId", userId);
    }

    /** 获取会话摘要（含最近消息上下文） */
    @GetMapping("/session/{id}")
    public SessionSummary getSession(@PathVariable String id) {
        SessionSummary summary = sessionMemoryService.buildSessionSummary(id);
        if (summary == null) throw new RuntimeException("会话不存在: " + id);
        return summary;
    }

    /** 获取会话历史消息列表 */
    @GetMapping("/session/{id}/messages")
    public List<Message> getMessages(@PathVariable String id) {
        return messageRepository.selectList(
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getSessionId, id)
                        .orderByAsc(Message::getCreatedAt));
    }

    /** 关闭会话 */
    @DeleteMapping("/session/{id}")
    public Map<String, String> closeSession(@PathVariable String id) {
        Session session = sessionRepository.selectById(id);
        if (session != null) {
            session.setStatus("closed");
            sessionRepository.updateById(session);
        }
        return Map.of("status", "ok");
    }

    /**
     * 手动转人工（不经过 Graph 工作流）
     *
     * 直接插入排队表，更新会话状态为 human_waiting。
     * 客服在 agent.html 上看到后点击"接入"即可开始对话。
     */
    @PostMapping("/session/{id}/transfer-human")
    public Map<String, Object> transferHuman(@PathVariable String id,
                                              @RequestBody Map<String, String> body) {
        String reason = body.getOrDefault("reason", "用户主动请求转人工");
        String userId = body.getOrDefault("userId", "");

        HumanQueueItem item = HumanQueueItem.builder()
                .sessionId(id).userId(userId).transferReason(reason).priority(5).status("waiting").build();
        humanQueueRepository.insert(item);

        long queueNumber = humanQueueRepository.selectCount(
                new LambdaQueryWrapper<HumanQueueItem>().eq(HumanQueueItem::getStatus, "waiting"));

        Session session = sessionRepository.selectById(id);
        if (session != null) {
            session.setStatus("human_waiting");
            sessionRepository.updateById(session);
        }

        return Map.of("status", "queued", "queueNumber", queueNumber, "waitTime", queueNumber * 60);
    }

    /** 查询当前排队状态 */
    @GetMapping("/human/queue/status")
    public Map<String, Object> getQueueStatus() {
        long waiting = humanQueueRepository.selectCount(
                new LambdaQueryWrapper<HumanQueueItem>().eq(HumanQueueItem::getStatus, "waiting"));
        return Map.of("waitingCount", waiting, "estimatedWaitTime", waiting * 60);
    }

    /** 离线留言 */
    @PostMapping("/human/leave-message")
    public Map<String, String> leaveMessage(@RequestBody Map<String, String> body) {
        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        String content = body.getOrDefault("content", "");
        String phone = body.getOrDefault("phone", "");
        String email = body.getOrDefault("email", "");

        Session session = Session.builder().id(sessionId)
                .userId(body.getOrDefault("userId", "anonymous")).channel("offline").status("closed").build();
        sessionRepository.insert(session);

        Message msg = Message.builder().sessionId(sessionId).role("user")
                .content("【离线留言】手机:" + phone + " 邮箱:" + email + " 内容:" + content)
                .nodeName("leave_message").build();
        messageRepository.insert(msg);

        return Map.of("status", "ok", "ticketId", sessionId);
    }
}
