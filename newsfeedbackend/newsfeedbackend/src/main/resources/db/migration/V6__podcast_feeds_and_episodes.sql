-- ─────────────────────────────────────────────────────────────────────
-- V6: Podcast RSS ingestie. Analoog aan rss_feeds en rss_items, maar met
-- async status-pipeline PENDING → DOWNLOADING → TRANSCRIBING → SUMMARIZING → DONE.
-- ─────────────────────────────────────────────────────────────────────

-- Podcast-feeds (per-user). Bevat podcast RSS URLs met per-feed transcription toggle.
CREATE TABLE podcast_feeds (
    username            TEXT NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    feed_url            TEXT NOT NULL,
    transcribe_enabled  BOOLEAN NOT NULL DEFAULT true,
    sort_order          INT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (username, feed_url)
);
CREATE INDEX podcast_feeds_created_idx ON podcast_feeds (username, created_at DESC);

-- Podcast-episodes (per-user). Status-pipeline via PENDING → DOWNLOADING → TRANSCRIBING → SUMMARIZING → DONE.
-- Episode-GUID is het caching-sleutel om duplicates te voorkomen.
CREATE TABLE podcast_episodes (
    username        TEXT NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    guid            TEXT NOT NULL,
    feed_url        TEXT NOT NULL,
    title           TEXT NOT NULL,
    description     TEXT NOT NULL DEFAULT '',
    status          TEXT NOT NULL DEFAULT 'PENDING',
    podcast_url     TEXT,
    transcript      TEXT,
    show_notes      TEXT,
    feed_item_id    TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    error_message   TEXT,
    PRIMARY KEY (username, guid),
    FOREIGN KEY (username, feed_url) REFERENCES podcast_feeds(username, feed_url) ON DELETE CASCADE
);
CREATE INDEX podcast_episodes_status_idx ON podcast_episodes (username, status);
CREATE INDEX podcast_episodes_created_idx ON podcast_episodes (username, created_at DESC);
CREATE INDEX podcast_episodes_completed_idx ON podcast_episodes (username, completed_at DESC) WHERE status = 'DONE';
