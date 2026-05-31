package com.example.customeragent.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChatRequest {

    @NotBlank
    private String sessionId;

    @NotBlank
    private String userId;

    @NotBlank
    private String content;

    private String channel = "web";
}
