package com.example.customeragent.model.dto;

import com.example.customeragent.model.EmotionType;
import com.example.customeragent.model.IntentType;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
public class SessionSummary {

    private String sessionId;
    private String userId;
    private String channel;
    private EmotionType emotionState;
    private IntentType currentIntent;
    private int negativeRounds;
    private String status;
    private String aiSummary;
    private String transferReason;
    private LocalDateTime createdAt;
}
