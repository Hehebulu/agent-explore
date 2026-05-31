package com.example.customeragent.model.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class EmotionResult {

    private String emotionType;
    private BigDecimal emotionScore;
    private boolean escalated;
    private String escalationReason;
}
