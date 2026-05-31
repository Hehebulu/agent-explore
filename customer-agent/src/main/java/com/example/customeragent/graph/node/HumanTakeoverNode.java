package com.example.customeragent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.customeragent.model.HumanQueueItem;
import com.example.customeragent.repository.HumanQueueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class HumanTakeoverNode implements AsyncNodeActionWithConfig {

    private static final Logger logger = LoggerFactory.getLogger(HumanTakeoverNode.class);
    private final HumanQueueRepository humanQueueRepository;

    public HumanTakeoverNode(HumanQueueRepository humanQueueRepository) {
        this.humanQueueRepository = humanQueueRepository;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state, RunnableConfig config) {
        String sessionId = state.value("session_id", "");
        String userId = state.value("user_id", "");
        String transferReason = state.value("transfer_reason").map(Object::toString).orElse("系统自动转接");
        boolean escalated = state.value("need_human").map(v -> (Boolean) v).orElse(false);

        logger.info("人工接管: sessionId={}, reason={}", sessionId, transferReason);

        HumanQueueItem queueItem = HumanQueueItem.builder()
                .sessionId(sessionId)
                .userId(userId)
                .priority(escalated ? 10 : 0)
                .transferReason(transferReason)
                .status("waiting")
                .build();
        humanQueueRepository.insert(queueItem);

        long queueNumber = humanQueueRepository.selectCount(
                new LambdaQueryWrapper<HumanQueueItem>().eq(HumanQueueItem::getStatus, "waiting"));
        long waitTime = queueNumber * 60;

        String message = String.format(
                "正在为您转接人工客服，当前排队 %d 人，预计等待 %d 秒。",
                queueNumber, waitTime);

        return CompletableFuture.completedFuture(Map.of(
                "ai_response", message,
                "human_queue_id", queueItem.getId(),
                "human_queue_number", (int) queueNumber,
                "human_wait_time", (int) waitTime
        ));
    }
}
