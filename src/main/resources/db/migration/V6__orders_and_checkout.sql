-- V6__orders_and_checkout.sql: Orders, order items, status history, and payments.

CREATE TABLE orders (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_number        VARCHAR(32)    NOT NULL,
    user_id             BIGINT         NOT NULL,
    status              VARCHAR(32)    NOT NULL,
    subtotal            NUMERIC(12, 2) NOT NULL,
    points_redeemed     BIGINT         NOT NULL DEFAULT 0,
    points_discount     NUMERIC(12, 2) NOT NULL DEFAULT 0.00,
    total               NUMERIC(12, 2) NOT NULL,
    points_earned       BIGINT         NOT NULL DEFAULT 0,
    shipping_full_name  VARCHAR(200)   NOT NULL,
    shipping_phone      VARCHAR(20)    NOT NULL,
    shipping_line1      VARCHAR(300)   NOT NULL,
    shipping_line2      VARCHAR(300),
    shipping_city       VARCHAR(100)   NOT NULL,
    shipping_state      VARCHAR(100)   NOT NULL,
    shipping_postal_code VARCHAR(20)   NOT NULL,
    shipping_country    VARCHAR(100)   NOT NULL,
    idempotency_key     VARCHAR(64),
    request_fingerprint VARCHAR(64),
    version             BIGINT         NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uk_orders_order_number UNIQUE (order_number),
    CONSTRAINT uk_orders_user_idempotency_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_orders_user_created ON orders (user_id, created_at DESC);
CREATE INDEX idx_orders_status ON orders (status);

CREATE TABLE order_items (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id       BIGINT         NOT NULL,
    book_id        BIGINT         NOT NULL,
    isbn_snapshot  VARCHAR(20)    NOT NULL,
    title_snapshot VARCHAR(300)   NOT NULL,
    unit_price     NUMERIC(12, 2) NOT NULL,
    quantity       INTEGER        NOT NULL,
    line_total     NUMERIC(12, 2) NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_order_items_book FOREIGN KEY (book_id) REFERENCES books (id),
    CONSTRAINT ck_order_items_quantity CHECK (quantity >= 1)
);

CREATE INDEX idx_order_items_order ON order_items (order_id);

CREATE TABLE order_status_history (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id    BIGINT       NOT NULL,
    from_status VARCHAR(32),
    to_status   VARCHAR(32)  NOT NULL,
    changed_by  VARCHAR(100) NOT NULL,
    reason      VARCHAR(500),
    changed_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_order_status_history_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE
);

CREATE INDEX idx_order_status_history_order ON order_status_history (order_id);

CREATE TABLE payments (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id          BIGINT         NOT NULL,
    amount            NUMERIC(12, 2) NOT NULL,
    status            VARCHAR(32)    NOT NULL,
    gateway_reference VARCHAR(64)    NOT NULL,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT fk_payments_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE
);

CREATE INDEX idx_payments_order ON payments (order_id);
