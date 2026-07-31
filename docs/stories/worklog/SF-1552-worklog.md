# SF-1552 - Worklog

Story-context bij eerste pickup:
RSS-feeds-editor gelijktrekken met podcast-editor (faalcontract, busy-state, message-extractie, docs)

1) frontend/lib/providers/data_providers.dart: RssFeedsNotifier.save (:197-202) omdraaien naar het model van PodcastFeedsNotifier.save (:230-237) - eerst PUT /api/rss-feeds, dan LocalCache.saveObject, pas daarna state = AsyncData(feeds); WHY-comment toevoegen dat state/cache bij een 400 ongewijzigd blijven en de exception naar de caller propageert.
2) frontend/lib/screens/rss_feeds_screen.dart: _RssFeedsEditorState (:50-88) een bool _busy geven en een Future<void> _save(List<String> next, {String? validateFailureMessage}) naar het model van _PodcastFeedsEditorState._save (:181-202): await op de notifier-save, _controller.clear() alleen bij succes van een toevoeg-actie (validateFailureMessage != null), try/catch met mounted-guard en rode snackbar (colorScheme.error) met de servertekst bij ApiException statusCode 400, generieke fallback 'Fout bij opslaan: ...' anders, en finally die _busy terugzet. Verwijder-knop (:62-64) en _add() (:81-87) lopen beide via _save; de onvoorwaardelijke _controller.clear() op :86 verdwijnt.
3) Busy-affordances identiek aan podcast: TextField enabled: !_busy, onSubmitted uitgeschakeld tijdens _busy, verwijder-IconButton onPressed null tijdens _busy, en de +-knop tijdens _busy vervangen door een 18x18 CircularProgressIndicator(strokeWidth: 2).
4) _extractDutchMessage (:206-214) promoveren naar een private top-level functie in rss_feeds_screen.dart die beide editors gebruiken, met de bug gefixt: regex zoekt op het JSON-veld "error" i.p.v. "message" (GlobalExceptionHandler in common/Exceptions.kt geeft overal {"error": "..."}). Raw-body- en lege-body-fallback behouden (maak de lege-body-fallbacktekst zo nodig meegeefbaar zodat hij voor beide editors klopt) en de nu-onjuiste doc-comment over Spring's message-veld corrigeren.
5) frontend/test/rss_feeds_screen_test.dart uitbreiden met faal-paden: fake-notifier die ApiException(400, '{"error":"Ongeldige RSS-feed-URL ..."}') gooit. Assert bij toevoegen: snackbar toont de servertekst (niet de rauwe JSON), de URL staat niet in de lijst, het invoerveld behoudt de tekst. Idem een verwijder-faal-pad (feed blijft zichtbaar + snackbar). Bestaande succespad-tests moeten groen blijven.
6) specs/frontend-spec.md §9a, RSS-blok (~regel 385): de invoerveld/toevoegen-bullet (en de verwijder-bullet) aanvullen met server-side validatie, spinner/disabled tijdens opslaan en rode snackbar bij afwijzing - analoog aan de podcast-bullet (~393).
7) e2e/scenarios/settings-scenario.md §3a: verwachting aanvullen naar analogie van §3b (regel 51): een door SsrfUrlValidator geweigerde URL (bv. loopback of niet-http(s)) geeft een rode snackbar met Nederlandse foutmelding, de URL wordt NIET toegevoegd en het invoerveld behoudt de tekst. Werk ook de RSS-regel in 'Verwacht resultaat' bij.
Randvoorwaarden: geen enkel bestand onder newsfeedbackend/ wijzigen; de twee editors blijven gescheiden widgets (alleen de message-extractie wordt gedeeld); gedrag bij geldige URL's blijft identiek. Sluit af met een zelf-review en draai 'cd frontend && flutter test' plus 'flutter analyze'; werk docs/stories/worklog/SF-1552-worklog.md bij.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- Uitgebreide story-log: docs/stories/SF-1552-rss-feeds-editor-faalcontract.md.

Uitvoering (subtaak SF-1577, developer):
- frontend/lib/providers/data_providers.dart: RssFeedsNotifier.save muteert state pas na
  geslaagde PUT + LocalCache.saveObject; WHY-comment toegevoegd. Bij een 400 blijven state en
  cache ongewijzigd en propageert de ApiException naar de caller.
- frontend/lib/screens/rss_feeds_screen.dart: _RssFeedsEditorState heeft nu _busy +
  Future<void> _save(next, {validateFailureMessage}) naar het model van de podcast-editor
  (await, try/catch met mounted-guard, rode snackbar op colorScheme.error, finally die _busy
  terugzet). Verwijderknop en _add() lopen beide via _save; _controller.clear() gebeurt alleen
  nog bij een geslaagde toevoeg-actie. Busy-affordances identiek aan podcast (veld disabled,
  onSubmitted/verwijderknop uit, 18x18 CircularProgressIndicator i.p.v. de +-knop).
- Message-extractie is een private top-level functie geworden die beide editors gebruiken, met
  de bug gefixt: regex zoekt nu op "error" i.p.v. "message" (GlobalExceptionHandler geeft overal
  {"error": "..."}). Raw-body-fallback behouden; de lege-body-fallbacktekst is meegeefbaar
  (emptyFallback). De onjuiste doc-comment over Spring's message-veld is herschreven.
- frontend/test/rss_feeds_screen_test.dart: vier tests toegevoegd (toevoegen-faalpad,
  verwijder-faalpad, busy-state met spinner/disabled veld, podcast-faalpad dat de gedeelde
  "error"-extractie bewijst). Bij het toevoegen-faalpad wordt de "niet in de lijst"-assertie
  bewust binnen ListTile gedaan, omdat de URL wel in het invoerveld hoort te blijven staan.
- specs/frontend-spec.md §9a (RSS-blok + aanscherping podcast-blok) en
  e2e/scenarios/settings-scenario.md §3a + "Verwacht resultaat" bijgewerkt.

Verificatie:
- cd frontend && flutter test -> 19 tests groen (was 15), All tests passed.
- cd frontend && flutter analyze -> 7 pre-existing info-meldingen, geen enkele in de gewijzigde
  bestanden, geen nieuwe meldingen.
- cd newsfeedbackend/newsfeedbackend && mvn -B --no-transfer-progress clean verify (het vangnet
  uit .factory/verification.yaml) -> BUILD SUCCESS, 80 unit-tests + 65 e2e-tests, 0 failures,
  0 errors. Opmerking: `docker info` faalde (docker-CLI niet beschikbaar), maar Testcontainers
  kon de Postgres-container wel starten en de volledige e2e-suite draaide.
- Geen bestand onder newsfeedbackend/ gewijzigd; frontend/pubspec.lock ongewijzigd (geen
  manifest-wijziging, dus geen lockfile-drift).

Review (subtaak SF-1577, reviewer):
- Volledige story-diff t.o.v. main beoordeeld (7 bestanden, geen backend-, OpenAPI- of
  Flyway-wijziging; Modulith-moduleregels niet geraakt).
- AC 1-9 nagelopen: save-volgorde in RssFeedsNotifier is 1-op-1 gelijk aan
  PodcastFeedsNotifier (PUT -> LocalCache -> state), _save/busy-affordances en de gedeelde
  _extractDutchMessage komen overeen met de podcast-variant. De "error"-i.p.v.-"message"-fix
  klopt met GlobalExceptionHandler (common/Exceptions.kt: overal mapOf("error" to ...)) en met
  de 400-melding uit SettingsServiceImpl ("Ongeldige RSS-feed-URL '...': ...").
- Gerichte hercontrole gedraaid: `flutter test` -> 19/19 groen; `flutter analyze` -> 7
  pre-existing info-meldingen, geen in de gewijzigde bestanden; werkboom bleef schoon
  (geen pubspec.lock-drift).
- Niet-blokkerende suggesties: (a) _extractDutchMessage matcht met regex `"([^"]+)"` en kapt af
  bij een ge-escapete quote in de servertekst - jsonDecode zou robuuster zijn; (b) bij een
  toevoeg-actie die op een niet-400-fout stukloopt toont de snackbar alleen
  'Kon feed niet opslaan' zonder detail (bewust gespiegeld aan podcast).

Test (subtaak SF-1578, tester):
- Inlogmodus: FALLBACK wegwerp-account `tester_sf-1552`. De vaste test-user-creds waren niet
  resolvebaar: `oc get secret newsfeed-api-keys -n pnf-pr-195` gaf Forbidden voor
  system:serviceaccount:agent-access:claude-agent en TESTER_USERNAME/TESTER_PASSWORD waren leeg.
  Account via de UI geregistreerd en na afloop van elke run opgeruimd via
  DELETE /api/account/me -> 200 (laatste run: opgeruimd).
- Preview pnf-pr-195 draait de juiste revisie: "Over deze app" toont frontend-buildhash
  ba69254 = branch-HEAD (screenshot 02-settings-top.png).
- AC1 (live): RSS-editor, URL `http://127.0.0.1/rss` + '+' -> PUT /api/rss-feeds gaf
  400 {"error":"Ongeldige RSS-feed-URL 'http://127.0.0.1/rss': host '127.0.0.1' resolvet naar
  een niet-toegestaan adres (loopback: 127.0.0.1)"}; rode snackbar toont exact die Nederlandse
  tekst (geen rauwe JSON), de URL staat NIET in de lijst en het invoerveld behoudt de tekst
  (13-invalid-snackbar.png). Na het weglopen van de snackbar is de lijst nog steeds leeg
  (14-after-snackbar.png).
- AC2 (live): geldige URL `https://feeds.bbci.co.uk/news/rss.xml` -> PUT 200, verschijnt in de
  lijst en het invoerveld is leeg (15-valid-added.png / 22-valid-added.png).
- AC3 (live, succespad): kruisje naast die feed -> PUT 200 {"feeds":[]}, feed verdwijnt
  (23-after-delete.png). Het faalpad bij verwijderen is niet live uit te lokken (de backend
  weigert een kleinere, geldige lijst niet) en is gedekt door de nieuwe widgettest.
- AC5 (live): met een kunstmatig vertraagde PUT (Playwright-route-delay, app ongewijzigd) is de
  busy-state zichtbaar: de +-knop van de RSS-editor is vervangen door een spinner en het
  invoerveld is uitgeschakeld/grijs; de podcast-editor blijft normaal (30-busy-spinner.png).
- AC6: GlobalExceptionHandler serialiseert overal naar {"error": ...} (geverifieerd in
  common/Exceptions.kt) en de live-400 hierboven bewijst de extractie end-to-end.
- AC7: `cd frontend && flutter test` -> 19/19 groen, All tests passed.
- AC8/AC9: specs/frontend-spec.md en e2e/scenarios/settings-scenario.md §3a bevatten de
  aangevulde verwachtingen; `git diff main...HEAD --name-only -- newsfeedbackend` is leeg.
- Werkboom bleef schoon (geen pubspec.lock-drift na `flutter test`).
- Screenshots: /work/screenshots (00-31).
