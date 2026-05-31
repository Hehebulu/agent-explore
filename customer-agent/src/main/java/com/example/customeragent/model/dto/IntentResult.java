package com.example.customeragent.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class IntentResult {

    private String intent;
    private double confidence;
    private String clarification;
}
