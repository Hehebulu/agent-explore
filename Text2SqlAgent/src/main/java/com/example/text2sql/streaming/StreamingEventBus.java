package com.example.text2sql.streaming;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流式事件总线 — 桥接 Node 内部的流式输出到 SSE Controller
 *
 * 每个会话注册一个 Sinks.Many，Node 在执行过程中将 token 级
 * 输出写入 Sink，Controller 从 Sink 读取并推送到前端 SSE 流。
 */
@Component
public class StreamingEventBus {

    private static final Logger logger = LoggerFactory.getLogger(StreamingEventBus.class);

    private final ConcurrentHashMap<String, Sinks.Many<Map<String, Object>>> sessionSinks = new ConcurrentHashMap<>();

    /**
     * 为指定会话注册一个 Sink，返回对应的 Flux 供 Controller 订阅
     */
    public Sinks.Many<Map<String, Object>> register(String sessionId) {
        Sinks.Many<Map<String, Object>> sink = Sinks.many().unicast().onBackpressureBuffer();
        sessionSinks.put(sessionId, sink);
        logger.debug("注册流式会话: {}", sessionId);
        return sink;
    }

    /**
     * 发送流式事件
     */
    public void emit(String sessionId, String node, String content) {
        Sinks.Many<Map<String, Object>> sink = sessionSinks.get(sessionId);
        if (sink != null) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("node", node);
            event.put("type", "stream");
            event.put("content", content);
            sink.tryEmitNext(event);
        }
    }

    /**
     * 发送流开始事件（前端可用于显示"正在生成..."提示）
     */
    public void emitStart(String sessionId, String node, String message) {
        Sinks.Many<Map<String, Object>> sink = sessionSinks.get(sessionId);
        if (sink != null) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("node", node);
            event.put("type", "stream_start");
            event.put("message", message);
            sink.tryEmitNext(event);
        }
    }

    /**
     * 发送节点开始事件（前端工作流状态栏实时更新）
     */
    public void emitNodeStart(String sessionId, String node, String message) {
        Sinks.Many<Map<String, Object>> sink = sessionSinks.get(sessionId);
        if (sink != null) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("node", node);
            event.put("type", "node_start");
            event.put("message", message);
            sink.tryEmitNext(event);
        }
    }

    /**
     * 发送流结束事件
     */
    public void emitEnd(String sessionId, String node) {
        Sinks.Many<Map<String, Object>> sink = sessionSinks.get(sessionId);
        if (sink != null) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("node", node);
            event.put("type", "stream_end");
            sink.tryEmitNext(event);
        }
    }

    /**
     * 发送中断事件（安全网：确保 HITL 审批信息到达前端）
     */
    public void emitInterruption(String sessionId, Map<String, Object> eventData) {
        Sinks.Many<Map<String, Object>> sink = sessionSinks.get(sessionId);
        if (sink != null) {
            sink.tryEmitNext(eventData);
        }
    }

    /**
     * 注销会话（不关闭 Sink，由 Controller 管理生命周期）
     */
    public void unregister(String sessionId) {
        sessionSinks.remove(sessionId);
        logger.debug("注销流式会话: {}", sessionId);
    }
}
