-- ─────────────────────────────────────────────────────────────────────
-- V1: initiële schema voor de Postgres-backed storage. Spiegelt 1:1 de
-- domain-classes (FeedItem, RssItem, NewsRequest, Podcast, User, ...).
-- Korte lijst-velden (topics, sourceRssIds, categoryResults) staan als
-- JSONB; ze worden altijd samen met de parent gelezen, niet apart
-- gequeried.
--
-- Sleutel-keuze: per-user tabellen hebben PK (username, id). De FK op
-- users(username) ON DELETE CASCADE zorgt dat het verwijderen van een
-- user automatisch alle bijbehorende data weghaalt — gelijk gedrag met
-- het oude `rm -rf data/users/<u>`-pad in cleanup-scenario.
-- ─────────────────────────────────────────────────────────────────────

-- Users (globaal)
CREATE TABLE users (
    id            TEXT PRIMARY KEY,
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    role          TEXT NOT NULL DEFAULT 'user',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- RSS-items (per-user)
CREATE TABLE rss_items (
    username        TEXT NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    id              TEXT NOT NULL,
    title           TEXT NOT NULL,
    summary         TEXT NOT NULL DEFAULT '',
    url             TEXT NOT NULL,
    category        TEXT NOT NULL DEFAULT 'overig',
    feed_url        TEXT NOT NULL DEFAULT '',
    source          TEXT NOT NULL DEFAULT '',
    snippet         TEXT NOT NULL DEFAULT '',
    published_date  TEXT,
    timestamp       TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ,
    in_feed         BOOLEAN NOT NULL DEFAULT false,
    feed_reason     TEXT NOT NULL DEFAULT '',
    is_read         BOOLEAN NOT NULL DEFAULT false,
    starred         BOOLEAN NOT NULL DEFAULT false,
    liked           BOOLEAN,
    topics          JSONB NOT NULL DEFAULT '[]'::jsonb,
    feed_item_id    TEXT,
    PRIMARY KEY (username, id)
);
CREATE INDEX rss_items_timestamp_idx ON rss_items (username, timestamp DESC);
CREATE INDEX rss_items_unread_idx    ON rss_items (username) WHERE is_read = false;
CREATE INDEX rss_items_in_feed_idx   ON rss_items (username) WHERE in_feed = true;

-- Feed-items (per-user)
CREATE TABLE feed_items (
    username        TEXT NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    id              TEXT NOT NULL,
    title           TEXT NOT NULL,
    title_nl        TEXT NOT NULL DEFAULT '',
    summary         TEXT NOT NULL,
    short_summary   TEXT NOT NULL DEFAULT '',
    url             TEXT,
    category        TEXT NOT NULL DEFAULT 'overig',
    source          TEXT NOT NULL DEFAULT '',
    source_rss_ids  JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_urls     JSONB NOT NULL DEFAULT '[]'::jsonb,
    topics          JSONB NOT NULL DEFAULT '[]'::jsonb,
    feed_reason     TEXT NOT NULL DEFAULT '',
    is_read         BOOLEAN NOT NULL DEFAULT false,
    starred         BOOLEAN NOT NULL DEFAULT false,
    liked           BOOLEAN,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_date  TEXT,
    is_summary      BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (username, id)
);
CREATE INDEX feed_items_created_idx ON feed_items (username, created_at DESC);
CREATE INDEX feed_items_unread_idx  ON feed_items (username) WHERE is_read = false;
CREATE INDEX feed_items_starred_idx ON feed_items (username) WHERE starred = true;
CREATE INDEX feed_items_summary_idx ON feed_items (username) WHERE is_summary = true;

-- News-requests (per-user)
CREATE TABLE news_requests (
    username              TEXT NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    id                    TEXT NOT NULL,
    subject               TEXT NOT NULL,
    source_item_id        TEXT,
    source_item_title     TEXT,
    preferred_count       INT  NOT NULL DEFAULT 2,
    max_count             INT  NOT NULL DEFAULT 5,
    extra_instructions    TEXT NOT NULL DEFAULT '',
    max_age_days          INT  NOT NULL DEFAULT 3,
    status                TEXT NOT NULL DEFAULT 'PENDING',
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at          TIMESTAMPTZ,
    new_item_count        INT  NOT NULL DEFAULT 0,
    is_hourly_update      BOOLEAN NOT NULL DEFAULT false,
    is_daily_summary      BOOLEAN NOT NULL DEFAULT false,
    category_results      JSONB NOT NULL DEFAULT '[]'::jsonb,
    processing_started_at TIMESTAMPTZ,
    duration_seconds      INT  NOT NULL DEFAULT 0,
    PRIMARY KEY (username, id)
);
CREATE INDEX news_requests_status_idx  ON news_requests (username, status);
CREATE INDEX news_requests_created_idx ON news_requests (username, created_at DESC);

-- Podcasts (per-user). Audio-files blijven op disk; we slaan alleen het pad op.
CREATE TABLE podcasts (
    username           TEXT NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    id                 TEXT NOT NULL,
    title              TEXT NOT NULL DEFAULT '',
    period_description TEXT NOT NULL DEFAULT '',
    period_days        INT  NOT NULL DEFAULT 7,
    duration_minutes   INT  NOT NULL DEFAULT 15,
    status             TEXT NOT NULL DEFAULT 'PENDING',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    script_text        TEXT,
    topics             JSONB NOT NULL DEFAULT '[]'::jsonb,
    audio_path         TEXT,
    duration_seconds   INT,
    custom_topics      JSONB NOT NULL DEFAULT '[]'::jsonb,
    tts_provider       TEXT NOT NULL DEFAULT 'OPENAI',
    podcast_number     INT  NOT NULL DEFAULT 0,
    generation_seconds INT,
    PRIMARY KEY (username, id)
);

-- Topic-history (per-user). Strings (Instant.toString()) uit JSON
-- converteren we naar TIMESTAMPTZ tijdens migratie.
CREATE TABLE topic_history (
    username              TEXT NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    topic                 TEXT NOT NULL,
    first_seen            TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_news        TIMESTAMPTZ,
    last_seen_podcast     TIMESTAMPTZ,
    news_count            INT NOT NULL DEFAULT 0,
    podcast_mention_count INT NOT NULL DEFAULT 0,
    podcast_deep_count    INT NOT NULL DEFAULT 0,
    liked_count           INT NOT NULL DEFAULT 0,
    starred_count         INT NOT NULL DEFAULT 0,
    PRIMARY KEY (username, topic)
);

-- Category-settings (per-user). sort_order behoudt array-volgorde uit JSON.
CREATE TABLE category_settings (
    username           TEXT NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    id                 TEXT NOT NULL,
    name               TEXT NOT NULL,
    enabled            BOOLEAN NOT NULL DEFAULT true,
    extra_instructions TEXT NOT NULL DEFAULT '',
    is_system          BOOLEAN NOT NULL DEFAULT false,
    sort_order         INT NOT NULL DEFAULT 0,
    PRIMARY KEY (username, id)
);

-- RSS-feeds-config (per-user)
CREATE TABLE rss_feeds (
    username   TEXT NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    url        TEXT NOT NULL,
    sort_order INT  NOT NULL DEFAULT 0,
    PRIMARY KEY (username, url)
);

-- External-calls (globaal audit/cost-log; geen FK op username zodat de
-- log de user kan overleven na cleanup).
CREATE TABLE external_calls (
    id            TEXT PRIMARY KEY,
    username      TEXT,
    provider      TEXT NOT NULL,
    action        TEXT NOT NULL,
    start_time    TIMESTAMPTZ NOT NULL,
    end_time      TIMESTAMPTZ NOT NULL,
    duration_ms   BIGINT NOT NULL,
    tokens_in     BIGINT,
    tokens_out    BIGINT,
    units         BIGINT,
    unit_type     TEXT NOT NULL,
    cost_usd      NUMERIC(12,6) NOT NULL DEFAULT 0,
    status        TEXT NOT NULL,
    error_message TEXT,
    subject       TEXT
);
CREATE INDEX external_calls_user_time_idx ON external_calls (username, start_time DESC);
CREATE INDEX external_calls_provider_idx  ON external_calls (provider, action, start_time DESC);

-- Marker-tabel voor eenmalige JSON→Postgres migratie. Migrator schrijft
-- hier een rij; daarna draait 'ie niet meer.
CREATE TABLE _migrations (
    name    TEXT PRIMARY KEY,
    done_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
