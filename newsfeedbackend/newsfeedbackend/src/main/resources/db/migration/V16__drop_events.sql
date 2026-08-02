-- ─────────────────────────────────────────────────────────────────────
-- V16 (SF-1746): de events-feature is volledig verwijderd.
--
-- De wekelijkse event-discovery (Tavily + OpenAI) was met ~85% veruit de
-- grootste kostenpost van de app terwijl de feature niet gebruikt werd.
-- Alle bijbehorende code (package `events`, de event-instellingen in
-- `settings` en de Flutter-schermen) is weg; deze migratie ruimt de
-- bijbehorende tabellen op.
--
-- Volgorde is FK-veilig: event_videos verwijst naar events, dus die
-- gaat eerst. IF EXISTS zodat de migratie ook doorloopt op een database
-- waar de tabellen om welke reden dan ook al ontbreken.
--
-- Bewust NIET aangeraakt: `external_calls` (historische kostenregels
-- blijven zichtbaar in het admin-kostenscherm) en `feed_items` (eerder
-- door de EventFeedAnnouncer aangemaakte items mogen blijven staan).
-- De migraties V11–V14 blijven ongewijzigd; die zijn al in productie
-- toegepast.
-- ─────────────────────────────────────────────────────────────────────

DROP TABLE IF EXISTS event_videos;
DROP TABLE IF EXISTS events;
DROP TABLE IF EXISTS event_preferences;
DROP TABLE IF EXISTS event_denylist;
