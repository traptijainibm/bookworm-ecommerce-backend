-- Phase 2: user accounts for self-contained authentication (BCrypt hashes, roles, login lockout).
-- Schema only: no users are inserted here and there are no default credentials. The first administrator is
-- created at startup by the application only when BOOKWORM_ADMIN_EMAIL and BOOKWORM_ADMIN_PASSWORD are set.

CREATE TABLE users (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email                 VARCHAR(254) NOT NULL,
    password_hash         VARCHAR(100) NOT NULL,
    first_name            VARCHAR(100) NOT NULL,
    last_name             VARCHAR(100) NOT NULL,
    phone                 VARCHAR(10),
    role                  VARCHAR(20)  NOT NULL,
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_login_attempts INTEGER      NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_lowercase CHECK (email = lower(email)),
    CONSTRAINT ck_users_role CHECK (role IN ('CUSTOMER', 'ADMIN')),
    CONSTRAINT ck_users_phone_format CHECK (phone IS NULL OR phone ~ '^[0-9]{10}$'),
    CONSTRAINT ck_users_failed_attempts_non_negative CHECK (failed_login_attempts >= 0)
);
