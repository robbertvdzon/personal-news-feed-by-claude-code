-- ─────────────────────────────────────────────────────────────────────
-- V14 (KAN-68): event_preferences en event_denylist.
--
-- KAN-65 stuurde de event-discovery via de generieke category_settings.
-- Dat leverde te veel generieke AI/Bitcoin-conferenties op en miste de
-- bekende dev/devrel-events. KAN-68 voegt per-user 'event-voorkeuren'
-- toe — een vrij beheerbare lijst eigen-namen ('JavaOne', 'KotlinConf',
-- ...) die als primaire seed voor de Tavily/Claude-discovery dient.
--
-- event_preferences: vrije lijst van event-namen die de gebruiker wil
-- volgen. Naam is de PK; sort_order houdt UI-volgorde. Defaults bij
-- eerste GET (zie SettingsServiceImpl).
--
-- event_denylist: events die de gebruiker uit de Events-lijst heeft
-- verwijderd. Genormaliseerde id (zelfde slug als events.id, bv.
-- 'javaone-2026') zodat de discovery ze bij de volgende run kan
-- overslaan; name is een snapshot voor de Settings-UI ("'JavaOne 2026'
-- terugzetten?"). Per-user — twee users kunnen hetzelfde event
-- onafhankelijk op de denylist of weer eraf zetten.
-- ─────────────────────────────────────────────────────────────────────

CREATE TABLE event_preferences (
    username    TEXT NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    name        TEXT NOT NULL,
    sort_order  INT  NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (username, name)
);

CREATE TABLE event_denylist (
    username       TEXT NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    normalized_id  TEXT NOT NULL,
    name           TEXT NOT NULL DEFAULT '',
    added_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (username, normalized_id)
);
