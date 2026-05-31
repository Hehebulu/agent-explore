package com.example.customeragent.model;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum IntentType {
    QUERY_PRODUCT("query_product"),
    COMPARE_PRODUCT("compare_product"),
    QUERY_INVENTORY("query_inventory"),
    QUERY_ORDER("query_order"),
    MODIFY_ORDER("modify_order"),
    CANCEL_ORDER("cancel_order"),
    REFUND_ORDER("refund_order"),
    TRANSFER_HUMAN("transfer_human"),
    CHIT_CHAT("chit_chat"),
    COMPLAINT("complaint"),
    UNKNOWN("unknown");

    @EnumValue
    private final String value;

    IntentType(String value) { this.value = value; }

    public String getValue() { return value; }
}
