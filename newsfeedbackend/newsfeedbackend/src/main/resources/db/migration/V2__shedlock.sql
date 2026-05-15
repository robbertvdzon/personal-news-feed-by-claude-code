-- ─────────────────────────────────────────────────────────────────────
-- V2: ShedLock tabel voor gedistribueerde scheduled jobs.
-- Zorgt dat scheduled jobs maar eenmaal tegelijk draaien in een
-- multi-instance deployment (meerdere namespaces).
-- ─────────────────────────────────────────────────────────────────────

CREATE TABLE shedlock (
    name              VARCHAR(64) NOT NULL,
    lock_at           TIMESTAMPTZ NOT NULL,
    locked_at         TIMESTAMPTZ NOT NULL,
    locked_by         VARCHAR(255) NOT NULL,
    description       VARCHAR(1000),
    PRIMARY KEY (name)
);

CREATE INDEX shedlock_lock_at_idx ON shedlock (lock_at);
