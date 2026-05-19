-- ─────────────────────────────────────────────────────────────────────
-- V10 (KAN-63): podcasts kunnen nu ook een Nederlandse vertaling zijn
-- van een aflevering uit een RSS-podcast-feed. Voegt link-back-velden
-- toe naar de bron-aflevering, een error_message-veld voor failure-
-- feedback, en een index voor de idempotency-lookup ("bestaat er al
-- een vertaling voor dit episode-guid?").
--
-- Het status-veld is een gewone TEXT-kolom (geen CHECK-constraint), dus
-- de extra waarden TRANSLATING en TTS_GENERATING vereisen geen DDL —
-- alleen de backend-enum wordt uitgebreid.
-- ─────────────────────────────────────────────────────────────────────

ALTER TABLE podcasts
    ADD COLUMN IF NOT EXISTS translated_from_episode_guid  TEXT,
    ADD COLUMN IF NOT EXISTS translated_from_feed_url      TEXT,
    ADD COLUMN IF NOT EXISTS translated_from_feed_name     TEXT,
    ADD COLUMN IF NOT EXISTS translated_from_episode_title TEXT,
    ADD COLUMN IF NOT EXISTS translated_from_rss_item_id   TEXT,
    ADD COLUMN IF NOT EXISTS error_message                 TEXT;

-- Partial index voor de idempotency-check in PodcastTranslationService:
-- één lookup `(username, translated_from_episode_guid)` om te zien of
-- er voor deze user al een vertaling van deze RSS-aflevering bestaat.
CREATE INDEX IF NOT EXISTS podcasts_translated_from_idx
    ON podcasts (username, translated_from_episode_guid)
    WHERE translated_from_episode_guid IS NOT NULL;
