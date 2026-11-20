-- V7__loyalty.sql: Add points_balance to users and create points_transactions table.

ALTER TABLE users ADD COLUMN points_balance BIGINT NOT NULL DEFAULT 0;
ALTER TABLE users ADD CONSTRAINT ck_users_points_balance CHECK (points_balance >= 0);

CREATE TABLE points_transactions (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    order_id    BIGINT,
    type        VARCHAR(32) NOT NULL,
    points      BIGINT      NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_points_transactions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_points_transactions_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uk_points_transactions_order_type 
ON points_transactions (order_id, type) 
WHERE type IN ('REDEEM', 'EARN', 'RESTORE');

CREATE INDEX idx_points_transactions_user ON points_transactions (user_id);
