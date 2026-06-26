# SF-220 - Worklog

Story-context bij eerste pickup:
RSS- en podcast-secties naar nieuwe subpagina RssFeedsScreen

Maak frontend/lib/screens/rss_feeds_screen.dart met een eigen AppBar (titel 'RSS-feeds'). Verplaats de inline-secties 'RSS-feeds' (_RssFeedsEditor op rssFeedsProvider) en 'Podcast-bronnen' (_PodcastFeedsEditor, KAN-56, op podcastFeedsProvider) uit settings_screen.dart naar deze subpagina, inclusief sectiekoppen en .when()-loading/error-afhandeling; editor-gedrag blijft ongewijzigd. Vervang de twee secties op de settings-pagina door één navigatie-ListTile met Icon(Icons.chevron_right), consistent met de API-log/Admin-tiles, die via MaterialPageRoute naar RssFeedsScreen pusht. Houd de logout-invalidations (settings_screen.dart:46-52) intact. Ruim ongebruikt geworden imports op in settings_screen.dart en voeg benodigde imports toe aan het nieuwe bestand. Geen backend-, OpenAPI- of providerwijzigingen. Sluit af met een interne review-stap.

Stappenplan:
[ ]: read issue and target docs
[ ]: implement requested changes
[ ]: run relevant tests
[ ]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.

Review SF-312 (reviewer, 2026-06-26):
- [info] Volledige story-diff (git diff main...HEAD) beoordeeld. Editors 1-op-1 verplaatst naar rss_feeds_screen.dart; gedrag identiek aan origineel.
- [info] AC1-5 + AC7 voldaan: navigatie-ListTile met chevron, RSS/podcast add/open/delete + validatie-snackbar + transcriptie-toggle, logout-invalidations en providers intact, styling behouden, geen backend/OpenAPI/providerlogica (enkel stale doc-comment gefixt).
- [info] Ongebruikte imports opgeruimd (url_launcher verwijderd, models.dart blijft nodig voor VersionInfo). Geen dangling referenties naar de verplaatste editors.
- [info] flutter analyze/test niet lokaal gedraaid (runner zonder flutter-binary); CI valideert. Story-log schoon (geen JSON-artefacten). 4 widgettests dekken het nieuwe scherm.
- Resultaat: akkoord.
