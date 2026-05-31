package com.example.customeragent.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("customer_message")
public class Message {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private String sessionId;

    private String role;

    private String content;

    @TableField("emotion_type")
    private EmotionType emotionType;

    @TableField("emotion_score")
    private BigDecimal emotionScore;

    @TableField("intent_type")
    private IntentType intentType;

    private BigDecimal confidence;

    @TableField("faq_hit")
    private boolean faqHit;

    @TableField("node_name")
    private String nodeName;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
