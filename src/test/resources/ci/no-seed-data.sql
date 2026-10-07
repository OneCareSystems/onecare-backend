-- CI loads no seed data on purpose.
-- Tests build their own fixtures and assert on empty/known table states;
-- the local scenario seeds live in src/main/resources/data.sql and are for
-- manual (Postman) testing only.
SELECT 1;
