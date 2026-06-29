# SF-742 — Story-brede test (worklog)

Tester-verificatie van story **SF-740** / PR #165 (branch `ai/SF-740`).
Gedrag-neutrale code-kwaliteitsrefactor + build-output opschonen.

## Diff-scope (main...HEAD)
4 bestanden, geen onverwachte wijzigingen:
- `docs/stories/SF-741-code-kwaliteit-build-output.md` (story-log)
- `docs/stories/worklog/SF-740-worklog.md` (worklog)
- `frontend/android/app/build.gradle.kts` (code)
- `frontend-reader/android/app/build.gradle.kts` (code)

Geen `.kt`, geen Dart-`lib`, geen tests, geen `e2e/`, geen infra gewijzigd.

## Verificatie (code-inspectie)
- **AC1/AC3 — gedrag-neutraal, vangnet ongemoeid:**
  - Backend `src` ongewijzigd sinds SF-579 (`git diff de75274 HEAD -- .../src` = leeg).
  - `frontend/lib` + `frontend-reader/lib` ongewijzigd t.o.v. main (= leeg).
  - `e2e/` ongewijzigd t.o.v. main (= leeg). ✓
- **De enige code-wijziging** is in beide Android Gradle-buildbestanden identiek:
  het met KGP 2.1.0 deprecated `kotlinOptions { jvmTarget = JavaVersion.VERSION_11.toString() }`
  vervangen door de canonieke top-level DSL
  `kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_11 } }` + de bijbehorende import.
  - KGP-versie bevestigd: `org.jetbrains.kotlin.android` **2.1.0** in beide `settings.gradle.kts`
    → de deprecation-claim is reëel.
  - JVM-target blijft **11**; `compileOptions { source/targetCompatibility = VERSION_11 }`
    ongewijzigd. Plugin `kotlin-android` levert de `kotlin {}`-extensie → DSL geldig.
  - Beide bestanden zijn byte-identiek behalve `namespace`/`applicationId`. ✓
- **AC4 — warning-reductie Android:** niet lokaal verifieerbaar (runner zonder
  flutter/gradle/Android-SDK); door CI te valideren. Wijziging is behoudend
  (officiële vervang-DSL, zelfde target), introduceert geen nieuwe warning. (info)

## Preview-test (PR #165, `https://pnf-pr-165.vdzonsoftware.nl`)
Inlog-modus: **default vaste test-user** (`TESTER_USERNAME`/`TESTER_PASSWORD`
read-only uit `newsfeed-api-keys` in ns `pnf-pr-165`). Geen DB-mutatie,
geen wachtwoord-reset.
- Preview live: root → HTTP 200; backend geboot: `/api/feed` zonder token → HTTP 403
  (security actief).
- Login via Flutter-UI (Playwright, 420x900) geslaagd → geldige JWT in
  `localStorage['flutter.token']`; Feed laadt met test-user-data.
- Screenshots: `/work/screenshots/01-login.png`, `/work/screenshots/02-after-login.png`.

> Noot: de codewijziging raakt uitsluitend de **Android**-Gradle-build en kan het
> Flutter-**web**-preview (CanvasKit) per definitie niet beïnvloeden; de Dart-frontend
> is byte-identiek aan main. De preview-test dient als regressie-bewijs dat de app
> normaal boot en functioneert, niet als directe validatie van de Android-fix (CI).

## Oordeel
Alle acceptatiecriteria die lokaal verifieerbaar zijn, slagen. Diff is klein,
gedrag-neutraal, binnen scope; e2e/integratietests ongemoeid; preview functioneert.
AC4 (Android/Flutter warning-reductie) wordt door CI bevestigd — conform de
behoudende aard van de wijziging acceptabel. **Geen blockers, geen bugs.**
