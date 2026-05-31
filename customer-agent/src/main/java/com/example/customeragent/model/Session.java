package com.example.customeragent.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("customer_session")
public class Session {

    @TableId
    private String id;

    @TableField("user_id")
    private String userId;

    @TableField(fill = FieldFill.INSERT)
    private String channel;

    @TableField(fill = FieldFill.INSERT)
    private String status;

    @TableField("emotion_state")
    private EmotionType emotionState;

    @TableField("current_intent")
    private IntentType currentIntent;

    @TableField("negative_rounds")
    private int negativeRounds;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
