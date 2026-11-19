-- Phase 1: catalogue schema (categories, authors, books, book_authors), pg_trgm and indexes.
-- Schema only: no data is inserted here. Development seed data lives in db/seed (dev profile only).

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE categories (
    id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    slug VARCHAR(100) NOT NULL,
    CONSTRAINT uk_categories_name UNIQUE (name),
    CONSTRAINT uk_categories_slug UNIQUE (slug),
    CONSTRAINT ck_categories_slug_format CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);

CREATE TABLE authors (
    id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    CONSTRAINT uk_authors_name UNIQUE (name)
);

CREATE TABLE books (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    isbn           VARCHAR(13)    NOT NULL,
    title          VARCHAR(300)   NOT NULL,
    description    TEXT,
    price          NUMERIC(12, 2) NOT NULL,
    publisher      VARCHAR(200),
    published_date DATE,
    cover_url      VARCHAR(500),
    category_id    BIGINT         NOT NULL REFERENCES categories (id),
    stock_quantity INTEGER        NOT NULL DEFAULT 0,
    active         BOOLEAN        NOT NULL DEFAULT TRUE,
    version        BIGINT         NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uk_books_isbn UNIQUE (isbn),
    CONSTRAINT ck_books_isbn_format CHECK (isbn ~ '^([0-9]{13}|[0-9]{9}[0-9X])$'),
    CONSTRAINT ck_books_price_positive CHECK (price > 0),
    CONSTRAINT ck_books_stock_non_negative CHECK (stock_quantity >= 0)
);

CREATE TABLE book_authors (
    book_id   BIGINT NOT NULL REFERENCES books (id),
    author_id BIGINT NOT NULL REFERENCES authors (id),
    PRIMARY KEY (book_id, author_id)
);

-- Case-insensitive substring search (lower(col) LIKE '%q%') is served by trigram GIN indexes.
CREATE INDEX ix_books_title_trgm ON books USING gin (lower(title) gin_trgm_ops);
CREATE INDEX ix_authors_name_trgm ON authors USING gin (lower(name) gin_trgm_ops);

CREATE INDEX ix_books_category_id ON books (category_id);
CREATE INDEX ix_book_authors_author_id ON book_authors (author_id);
