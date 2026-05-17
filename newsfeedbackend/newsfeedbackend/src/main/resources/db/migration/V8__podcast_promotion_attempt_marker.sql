-- ─────────────────────────────────────────────────────────────────────
-- V8 (KAN-60 follow-up): markeer wanneer de 24h-show-notes-promotie
-- voor een aflevering al getriggerd is.
--
-- Reviewer-bug op de eerste KAN-60-iteratie: `findShowNotesExpiredForPromotion`
-- filterde alleen op `ri.feed_item_id IS NULL`. Dat veld blijft ook NULL
-- wanneer de AI het podcast-item AFWIJST (alleen `inFeed=false` + `feedReason`
-- worden gezet). Gevolg: elke worker-tick (default elke 2m) zag het item
-- nog steeds als 'nog te promoten' en triggerde opnieuw een Claude-call
-- → ≈ 720 selectie-calls per dag per afgewezen >24h-aflevering.
--
-- Deze kolom geeft de worker een ondubbelzinnige marker: zodra de
-- promotie 1x getriggerd is, doen we 'm voor deze aflevering niet meer
-- vanuit de show-notes-timeout-pad. NULL = nog niet geprobeerd; alle
-- bestaande rijen krijgen NULL (krijgen dus 1x kans op promotie als ze
-- al >24h vastzitten — gewenst, want het oude pad heeft ze überhaupt
-- nog niet correct getriggerd).
-- ─────────────────────────────────────────────────────────────────────

ALTER TABLE podcast_episodes
    ADD COLUMN IF NOT EXISTS feed_promotion_attempted_at TIMESTAMPTZ;
