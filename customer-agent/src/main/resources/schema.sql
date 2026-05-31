-- 会话表
CREATE TABLE IF NOT EXISTS customer_session (
    id          VARCHAR(64)  PRIMARY KEY,
    user_id     VARCHAR(64)  NOT NULL,
    channel     VARCHAR(32)  NOT NULL DEFAULT 'web',
    status      VARCHAR(32)  NOT NULL DEFAULT 'active',
    emotion_state VARCHAR(32),
    current_intent VARCHAR(64),
    negative_rounds INT NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now()
);

-- 消息表
CREATE TABLE IF NOT EXISTS customer_message (
    id            BIGSERIAL    PRIMARY KEY,
    session_id    VARCHAR(64)  NOT NULL REFERENCES customer_session(id),
    role          VARCHAR(16)  NOT NULL,
    content       TEXT         NOT NULL,
    emotion_type  VARCHAR(32),
    emotion_score DECIMAL(3,2),
    intent_type   VARCHAR(64),
    confidence    DECIMAL(3,2),
    faq_hit       BOOLEAN      DEFAULT false,
    node_name     VARCHAR(64),
    created_at    TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_message_session ON customer_message(session_id);

-- FAQ 知识库表
CREATE TABLE IF NOT EXISTS faq_knowledge (
    id        BIGSERIAL    PRIMARY KEY,
    question  VARCHAR(500) NOT NULL,
    answer    TEXT         NOT NULL,
    category  VARCHAR(64),
    enabled   BOOLEAN      DEFAULT true,
    created_at TIMESTAMP   NOT NULL DEFAULT now()
);

-- 敏感词表
CREATE TABLE IF NOT EXISTS sensitive_word (
    id       BIGSERIAL    PRIMARY KEY,
    word     VARCHAR(200) NOT NULL,
    category VARCHAR(32)  NOT NULL,
    enabled  BOOLEAN      DEFAULT true
);

-- 人工客服排队表
CREATE TABLE IF NOT EXISTS human_queue (
    id             BIGSERIAL    PRIMARY KEY,
    session_id     VARCHAR(64)  NOT NULL,
    user_id        VARCHAR(64)  NOT NULL,
    priority       INT          DEFAULT 0,
    transfer_reason VARCHAR(200),
    status         VARCHAR(32)  DEFAULT 'waiting',
    created_at     TIMESTAMP    NOT NULL DEFAULT now(),
    assigned_at    TIMESTAMP
);
