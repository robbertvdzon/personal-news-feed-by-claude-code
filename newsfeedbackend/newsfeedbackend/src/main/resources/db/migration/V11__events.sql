-- ─────────────────────────────────────────────────────────────────────
-- V11 (KAN-65): events-tabel. Per-user, gespiegeld op de Event-domain-
-- class. Wekelijks ontdekt met AI + Tavily op basis van de categorie-
-- settings. Dedup op de stabiele id (genormaliseerde naam + jaar, bv.
-- 'javaone-2026') per gebruiker — PK (username, id) zorgt dat een tweede
-- ontdekking dezelfde rij bijwerkt i.p.v. dupliceert.
--
-- source_links staat als JSONB (korte lijst, altijd samen met de parent
-- gelezen) net als topics/source_urls elders.
-- ─────────────────────────────────────────────────────────────────────

CREATE TABLE events (
    username      TEXT NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    id            TEXT NOT NULL,
    name          TEXT NOT NULL,
    organization  TEXT,
    start_date    TEXT,
    end_date      TEXT,
    location      TEXT NOT NULL DEFAULT '',
    description   TEXT NOT NULL DEFAULT '',
    source_links  JSONB NOT NULL DEFAULT '[]'::jsonb,
    category      TEXT NOT NULL DEFAULT 'overig',
    feed_item_id  TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (username, id)
);
CREATE INDEX events_start_date_idx ON events (username, start_date);
