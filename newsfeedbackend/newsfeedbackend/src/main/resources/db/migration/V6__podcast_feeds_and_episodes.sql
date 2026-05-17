-- ─────────────────────────────────────────────────────────────────────
-- V6 (KAN-56): podcast-bronnen + episode-ingestion.
--
-- Een eerdere V6 ("podcast_feeds_and_episodes") is op de gedeelde
-- preview-DB ooit toegepast en daarna handmatig teruggedraaid; de
-- tabel-creates staan daarom op CREATE TABLE IF NOT EXISTS zodat een
-- re-deploy op zo'n DB idempotent is.
--
-- Datamodel-keuze: aanpak (a) uit de story — podcast-afleveringen
-- verschijnen in de bestaande rss_items-tabel via een media_type-
-- discriminator. De ingestion-state (PENDING/DOWNLOADING/...) leeft in
-- een aparte podcast_episodes-tabel; daar staat ook de (username, guid)
-- cache-key voor idempotency. Pas wanneer een episode DONE is, schrijft
-- de pipeline een rij naar rss_items zodat de RSS-tab het kaartje toont.
-- Promotie naar de Feed-tab werkt via het bestaande
-- rss_items.feed_item_id-mechanisme — geen aparte code-pad nodig.
-- ─────────────────────────────────────────────────────────────────────

-- Per-user lijst met podcast-RSS-feed-URLs + per-feed transcribe-toggle.
CREATE TABLE IF NOT EXISTS podcast_feeds (
    username           TEXT NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    url                TEXT NOT NULL,
    transcribe_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order         INT NOT NULL DEFAULT 0,
    PRIMARY KEY (username, url)
);

-- Per-user lijst met (proceeded/proceeding) afleveringen. PK (username,
-- guid) is meteen de idempotency-cache: een refresh die dezelfde GUID
-- opnieuw tegenkomt vindt 'm al en triggert geen verwerking.
CREATE TABLE IF NOT EXISTS podcast_episodes (
    username         TEXT NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    guid             TEXT NOT NULL,
    feed_url         TEXT NOT NULL,
    podcast_name     TEXT NOT NULL DEFAULT '',
    title            TEXT NOT NULL DEFAULT '',
    audio_url        TEXT NOT NULL DEFAULT '',
    duration_seconds INT,
    published_date   TEXT,
    show_notes       TEXT NOT NULL DEFAULT '',
    transcript       TEXT NOT NULL DEFAULT '',
    summary          TEXT NOT NULL DEFAULT '',
    -- PENDING → DOWNLOADING → TRANSCRIBING → SUMMARIZING → DONE,
    -- met FAILED als terminale-fout-state.
    status           TEXT NOT NULL DEFAULT 'PENDING',
    error_message    TEXT,
    -- Gekoppelde rss_items.id zodra status=DONE (zodat de pipeline een
    -- bestaand kaartje kan vinden i.p.v. een dubbele aan te maken).
    rss_item_id      TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (username, guid)
);
CREATE INDEX IF NOT EXISTS podcast_episodes_status_idx
    ON podcast_episodes (username, status);
CREATE INDEX IF NOT EXISTS podcast_episodes_feed_idx
    ON podcast_episodes (username, feed_url);

-- rss_items uitbreiden zodat één tabel zowel artikelen als podcast-
-- afleveringen kan dragen. media_type='ARTICLE' (default) voor RSS-
-- artikelen, 'PODCAST' voor afleveringen. audio_url + duration_seconds
-- alleen relevant voor podcasts.
ALTER TABLE rss_items ADD COLUMN IF NOT EXISTS media_type TEXT NOT NULL DEFAULT 'ARTICLE';
ALTER TABLE rss_items ADD COLUMN IF NOT EXISTS audio_url TEXT NOT NULL DEFAULT '';
ALTER TABLE rss_items ADD COLUMN IF NOT EXISTS duration_seconds INT;
