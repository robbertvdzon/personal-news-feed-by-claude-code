-- KAN-55 isolation test table
-- This table is used to verify that preview-database-branches are isolated from production.

CREATE TABLE IF NOT EXISTS kan55_isolation_marker (
    id SERIAL PRIMARY KEY,
    message TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO kan55_isolation_marker (message) VALUES ('hello from KAN-55 test — pr-<pr_num> branch');
