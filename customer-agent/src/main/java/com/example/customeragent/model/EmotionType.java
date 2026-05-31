package com.example.customeragent.model;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum EmotionType {
    NORMAL("normal"),
    UNHAPPY("unhappy"),
    ANGRY("angry"),
    COMPLAINT("complaint"),
    ABUSE("abuse");

    @EnumValue
    private final String value;

    EmotionType(String value) { this.value = value; }

    public String getValue() { return value; }
}
