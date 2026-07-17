# SF-1031 - Sectiekop 'Account' → 'Account Settings' in settingspagina

Stappenplan:
[x]: lees .task.md, docs/factory en agent-tips
[x]: verifieer bestaande implementatie tegen scope
[x]: draai vangnet (backend mvn test + frontend flutter test)
[x]: verduidelijk pubspec.lock-discussie in worklog
[x]: story-log bijgewerkt

Done / rationale:
- De kernwijziging was al aanwezig op de branch: `frontend/lib/screens/settings_screen.dart:35` toont `'Account Settings'` i.p.v. `'Account'`, en `frontend/test/settings_screen_test.dart:85` gebruikt `find.text('Account Settings')` in de positie-assertie t.o.v. Categorieën/Weergave. Geen andere teksten/layout/logica/backend gewijzigd (bevestigd via grep op `'Account'` in `frontend/`, `e2e/`, `specs/`, `docs/`).
- In deze fabriek-omgeving zijn `flutter`/`dart` nu wél beschikbaar (afwijkend van eerdere agent-tips). `flutter pub get` + `flutter test` in `frontend/` draaien groen: 16/16 tests slagen, inclusief `settings_screen_test.dart`. `flutter pub get` wijzigt `pubspec.lock` niet t.o.v. de gecommitte staat, dus de lockfile is reproduceerbaar stabiel.
- Backend-vangnet `mvn test` (Maven-root `newsfeedbackend/newsfeedbackend/`) draait groen, exitcode 0. Backend is in deze story ongewijzigd.
- De eerder door reviewers gemelde `frontend/pubspec.lock`-blocker (11 transitieve dependency-bumps) is per issue comment 1274 expliciet door de product owner bevestigd als gewenst ("die zijn gewenst en moet niet terug"); de lockfile blijft dus ongewijzigd staan. Zie toelichting in `docs/stories/worklog/SF-987-worklog.md`.
- Geen verdere codewijzigingen nodig voor deze subtaak; de implementatie voldoet aan alle acceptatiecriteria.
