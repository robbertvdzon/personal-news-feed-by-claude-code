-- ─────────────────────────────────────────────────────────────────────
-- V6: podcast-bronnen (per-user lijst van podcast-RSS-feeds) en
-- podcast-afleveringen (per-user, per-guid cache). Afleveringen worden
-- async verwerkt door PodcastFeedPipeline: PENDING → DOWNLOADING →
-- TRANSCRIBING (optioneel) → SUMMARIZING → DONE. FAILED is een
-- terminale fout-state met error_message in dezelfde rij.
--
-- CREATE TABLE IF NOT EXISTS is bewust: een eerdere V6-poging is in de
-- gedeelde preview-DB teruggedraaid (zie .task.md); deze migratie moet
-- op zowel een schone als een gedeeltelijk-bestaande DB werken.
--
-- Idempotency: PK (username, guid) op podcast_episodes voorkomt dat
-- een refresh dezelfde aflevering nog eens als PENDING inserteert.
--
-- Naast de nieuwe tabellen breiden we feed_items uit met een handvol
-- kolommen zodat één feed-card kan onderscheiden of-ie van RSS of van
-- een podcast komt (kind), waar de MP3 staat (audio_url), hoe lang
-- (duration_seconds) en op basis waarvan de samenvatting is gemaakt
-- (summary_source: 'transcript' of 'show_notes').
-- ─────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS podcast_feeds (
    username           TEXT NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    url                TEXT NOT NULL,
    transcribe_enabled BOOLEAN NOT NULL DEFAULT true,
    sort_order         INT NOT NULL DEFAULT 0,
    added_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (username, url)
);

CREATE TABLE IF NOT EXISTS podcast_episodes (
    username        TEXT NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    guid            TEXT NOT NULL,
    feed_url        TEXT NOT NULL,
    title           TEXT NOT NULL DEFAULT '',
    podcast_name    TEXT NOT NULL DEFAULT '',
    audio_url       TEXT NOT NULL DEFAULT '',
    duration_seconds INT,
    description     TEXT NOT NULL DEFAULT '',
    transcript      TEXT,
    summary         TEXT,
    summary_source  TEXT,
    status          TEXT NOT NULL DEFAULT 'PENDING',
    error_message   TEXT,
    published_date  TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ,
    feed_item_id    TEXT,
    PRIMARY KEY (username, guid),
    FOREIGN KEY (username, feed_url) REFERENCES podcast_feeds(username, url) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS podcast_episodes_status_idx ON podcast_episodes (username, status);
CREATE INDEX IF NOT EXISTS podcast_episodes_feed_idx ON podcast_episodes (username, feed_url);
CREATE INDEX IF NOT EXISTS podcast_episodes_created_idx ON podcast_episodes (username, created_at DESC);

-- feed_items uitbreiden met podcast-metadata. Default kind='rss' zodat
-- bestaande regels zonder migratie-stap blijven werken.
ALTER TABLE feed_items ADD COLUMN IF NOT EXISTS kind             TEXT NOT NULL DEFAULT 'rss';
ALTER TABLE feed_items ADD COLUMN IF NOT EXISTS audio_url        TEXT;
ALTER TABLE feed_items ADD COLUMN IF NOT EXISTS duration_seconds INT;
ALTER TABLE feed_items ADD COLUMN IF NOT EXISTS summary_source   TEXT;
