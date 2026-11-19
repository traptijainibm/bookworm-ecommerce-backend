-- DEVELOPMENT SEED DATA ONLY. Loaded when the `dev` profile adds classpath:db/seed to the Flyway locations.
-- Never loaded by the default, test or prod profiles.
--
-- Rules for this file (safeguards agreed for Phase 1):
--   * Insert only. Every statement uses ON CONFLICT DO NOTHING and there is no UPDATE or DELETE, so rows
--     that already exist (for example records maintained by hand) are never overwritten.
--   * Re-runnable: Flyway re-applies a repeatable migration whenever this file's checksum changes.
--   * Author links are added only to books that currently have no authors, so a book whose authors were
--     edited by hand is left alone.
--   * ISBNs are SYNTHETIC (978-0-00-000NNN-C, valid ISBN-13 check digits) so they cannot clash with real
--     books. Titles and authors are real public-domain-style examples for realistic browsing; descriptions
--     are generic placeholders. Prices are INR, tax-inclusive.

INSERT INTO categories (name, slug) VALUES
    ('Classics',        'classics'),
    ('Fantasy',         'fantasy'),
    ('Fiction',         'fiction'),
    ('Mystery',         'mystery'),
    ('Non-Fiction',     'non-fiction'),
    ('Science Fiction', 'science-fiction')
ON CONFLICT DO NOTHING;

INSERT INTO authors (name) VALUES
    ('George Orwell'), ('Jane Austen'), ('Frank Herbert'), ('Isaac Asimov'), ('J.R.R. Tolkien'),
    ('George R.R. Martin'), ('Patrick Rothfuss'), ('Agatha Christie'), ('Arthur Conan Doyle'),
    ('Stieg Larsson'), ('Yuval Noah Harari'), ('James Clear'), ('Daniel Kahneman'), ('Harper Lee'),
    ('Khaled Hosseini'), ('Arundhati Roy'), ('Paulo Coelho'), ('Neil Gaiman'), ('Terry Pratchett'),
    ('William Gibson'), ('Ursula K. Le Guin'), ('Alex Michaelides'), ('Jawaharlal Nehru'), ('R.K. Narayan')
ON CONFLICT DO NOTHING;

INSERT INTO books (isbn, title, description, price, publisher, published_date, category_id, stock_quantity, active)
SELECT v.isbn, v.title, v.description, v.price, v.publisher, v.published_date::date, c.id, v.stock_quantity, v.active
FROM (VALUES
    ('9780000000019', 'Nineteen Eighty-Four',              'Development sample record.', 299.00, 'Dev Press', '1949-06-08', 'classics',        25, TRUE),
    ('9780000000026', 'Animal Farm',                       'Development sample record.', 199.00, 'Dev Press', '1945-08-17', 'classics',        40, TRUE),
    ('9780000000033', 'Pride and Prejudice',               'Development sample record.', 249.00, 'Dev Press', '1813-01-28', 'classics',        30, TRUE),
    ('9780000000040', 'Emma',                              'Development sample record.', 229.00, 'Dev Press', '1815-12-23', 'classics',        12, TRUE),
    ('9780000000057', 'Dune',                              'Development sample record.', 499.00, 'Dev Press', '1965-08-01', 'science-fiction', 18, TRUE),
    ('9780000000064', 'Dune Messiah',                      'Development sample record.', 449.00, 'Dev Press', '1969-10-15', 'science-fiction',  0, TRUE),
    ('9780000000071', 'Foundation',                        'Development sample record.', 399.00, 'Dev Press', '1951-06-01', 'science-fiction', 22, TRUE),
    ('9780000000088', 'I, Robot',                          'Development sample record.', 349.00, 'Dev Press', '1950-12-02', 'science-fiction', 15, TRUE),
    ('9780000000095', 'The Hobbit',                        'Development sample record.', 399.00, 'Dev Press', '1937-09-21', 'fantasy',         35, TRUE),
    ('9780000000101', 'The Fellowship of the Ring',        'Development sample record.', 549.00, 'Dev Press', '1954-07-29', 'fantasy',         20, TRUE),
    ('9780000000118', 'A Game of Thrones',                 'Development sample record.', 599.00, 'Dev Press', '1996-08-01', 'fantasy',         14, TRUE),
    ('9780000000125', 'The Name of the Wind',              'Development sample record.', 499.00, 'Dev Press', '2007-03-27', 'fantasy',          9, TRUE),
    ('9780000000132', 'Murder on the Orient Express',      'Development sample record.', 299.00, 'Dev Press', '1934-01-01', 'mystery',         28, TRUE),
    ('9780000000149', 'And Then There Were None',          'Development sample record.', 299.00, 'Dev Press', '1939-11-06', 'mystery',         26, TRUE),
    ('9780000000156', 'The Hound of the Baskervilles',     'Development sample record.', 249.00, 'Dev Press', '1902-04-01', 'mystery',         19, TRUE),
    ('9780000000163', 'The Girl with the Dragon Tattoo',   'Development sample record.', 449.00, 'Dev Press', '2005-08-01', 'mystery',         11, TRUE),
    ('9780000000170', 'Sapiens',                           'Development sample record.', 599.00, 'Dev Press', '2011-01-01', 'non-fiction',     33, TRUE),
    ('9780000000187', 'Homo Deus',                         'Development sample record.', 599.00, 'Dev Press', '2015-01-01', 'non-fiction',     17, TRUE),
    ('9780000000194', 'Atomic Habits',                     'Development sample record.', 499.00, 'Dev Press', '2018-10-16', 'non-fiction',     50, TRUE),
    ('9780000000200', 'Thinking, Fast and Slow',           'Development sample record.', 549.00, 'Dev Press', '2011-10-25', 'non-fiction',     21, TRUE),
    ('9780000000217', 'To Kill a Mockingbird',             'Development sample record.', 349.00, 'Dev Press', '1960-07-11', 'fiction',         24, TRUE),
    ('9780000000224', 'The Kite Runner',                   'Development sample record.', 399.00, 'Dev Press', '2003-05-29', 'fiction',         16, TRUE),
    ('9780000000231', 'The God of Small Things',           'Development sample record.', 399.00, 'Dev Press', '1997-04-04', 'fiction',         13, TRUE),
    ('9780000000248', 'The Alchemist',                     'Development sample record.', 299.00, 'Dev Press', '1988-01-01', 'fiction',         45, TRUE),
    ('9780000000255', 'Good Omens',                        'Development sample record.', 449.00, 'Dev Press', '1990-05-01', 'fantasy',         10, TRUE),
    ('9780000000262', 'Neuromancer',                       'Development sample record.', 399.00, 'Dev Press', '1984-07-01', 'science-fiction',  8, TRUE),
    ('9780000000279', 'The Left Hand of Darkness',         'Development sample record.', 429.00, 'Dev Press', '1969-03-01', 'science-fiction',  7, TRUE),
    ('9780000000286', 'The Silent Patient',                'Development sample record.', 399.00, 'Dev Press', '2019-02-05', 'mystery',         20, TRUE),
    ('9780000000293', 'The Discovery of India',            'Development sample record.', 449.00, 'Dev Press', '1946-01-01', 'non-fiction',      6, TRUE),
    ('9780000000309', 'Malgudi Days',                      'Development sample record.', 249.00, 'Dev Press', '1943-01-01', 'fiction',         27, TRUE),
    -- Inactive on purpose: lets you confirm withdrawn books never appear in search, details or related.
    ('9780000000316', 'Withdrawn Sample (inactive)',       'Development sample record.', 199.00, 'Dev Press', '1950-01-01', 'classics',         0, FALSE)
) AS v(isbn, title, description, price, publisher, published_date, category_slug, stock_quantity, active)
JOIN categories c ON c.slug = v.category_slug
ON CONFLICT DO NOTHING;

INSERT INTO book_authors (book_id, author_id)
SELECT b.id, a.id
FROM (VALUES
    ('9780000000019', 'George Orwell'),
    ('9780000000026', 'George Orwell'),
    ('9780000000033', 'Jane Austen'),
    ('9780000000040', 'Jane Austen'),
    ('9780000000057', 'Frank Herbert'),
    ('9780000000064', 'Frank Herbert'),
    ('9780000000071', 'Isaac Asimov'),
    ('9780000000088', 'Isaac Asimov'),
    ('9780000000095', 'J.R.R. Tolkien'),
    ('9780000000101', 'J.R.R. Tolkien'),
    ('9780000000118', 'George R.R. Martin'),
    ('9780000000125', 'Patrick Rothfuss'),
    ('9780000000132', 'Agatha Christie'),
    ('9780000000149', 'Agatha Christie'),
    ('9780000000156', 'Arthur Conan Doyle'),
    ('9780000000163', 'Stieg Larsson'),
    ('9780000000170', 'Yuval Noah Harari'),
    ('9780000000187', 'Yuval Noah Harari'),
    ('9780000000194', 'James Clear'),
    ('9780000000200', 'Daniel Kahneman'),
    ('9780000000217', 'Harper Lee'),
    ('9780000000224', 'Khaled Hosseini'),
    ('9780000000231', 'Arundhati Roy'),
    ('9780000000248', 'Paulo Coelho'),
    ('9780000000255', 'Neil Gaiman'),
    ('9780000000255', 'Terry Pratchett'),
    ('9780000000262', 'William Gibson'),
    ('9780000000279', 'Ursula K. Le Guin'),
    ('9780000000286', 'Alex Michaelides'),
    ('9780000000293', 'Jawaharlal Nehru'),
    ('9780000000309', 'R.K. Narayan'),
    ('9780000000316', 'George Orwell')
) AS v(isbn, author_name)
JOIN books b ON b.isbn = v.isbn
JOIN authors a ON a.name = v.author_name
WHERE NOT EXISTS (SELECT 1 FROM book_authors existing WHERE existing.book_id = b.id)
ON CONFLICT DO NOTHING;
