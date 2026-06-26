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
