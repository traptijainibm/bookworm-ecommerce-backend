-- Phase 3: constraints that back the admin catalogue writes. Schema only: no data is changed.

-- Case-insensitive uniqueness of author and category names (the existing exact-match unique
-- constraints stay in place).
CREATE UNIQUE INDEX ux_authors_name_ci ON authors (lower(name));
CREATE UNIQUE INDEX ux_categories_name_ci ON categories (lower(name));

-- Books are stored under the canonical ISBN-13 only (978/979 prefix). The application converts
-- ISBN-10 input and validates the checksum; the database enforces the stored shape.
ALTER TABLE books DROP CONSTRAINT ck_books_isbn_format;
ALTER TABLE books ADD CONSTRAINT ck_books_isbn_format CHECK (isbn ~ '^97[89][0-9]{10}$');
