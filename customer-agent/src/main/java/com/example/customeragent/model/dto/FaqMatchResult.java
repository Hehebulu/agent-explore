package com.example.customeragent.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FaqMatchResult {

    private boolean matched;
    private String question;
    private String answer;
    private double similarity;
}
