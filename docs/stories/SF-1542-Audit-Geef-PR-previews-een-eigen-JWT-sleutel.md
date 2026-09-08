# SF-1542 - [Audit] Geef PR-previews een eigen JWT-sleutel in plaats van de productiesleutel

## Story

[Audit] Geef PR-previews een eigen JWT-sleutel in plaats van de productiesleutel

<!-- refined-by-factory -->

## Samenvatting

Elke PR-preview krijgt op dit moment dezelfde JWT-ondertekensleutel als productie mee in zijn omgeving. Daardoor kan code op een willekeurige PR-branch een inlogtoken maken dat 30 dagen geldig is op de echte productieomgeving — ook als admin.

Deze story zorgt ervoor dat previews die productiesleutel niet meer krijgen. De backend maakt in dat geval zelf een tijdelijke sleutel aan bij het opstarten. Gevolg: previews werken gewoon, maar hun tokens gelden alleen binnen die preview en vervallen als de pod herstart. Dat is prima, want previews zijn wegwerp en de e2e-test logt per run opnieuw in.

Er is geen nieuw geheim nodig en er hoeft niets opnieuw versleuteld te worden; productie verandert niet.

## Scope

In scope — uitsluitend `deploy/overlays/preview/kustomization.yaml`:

- Voeg een extra entry toe aan de bestaande `patches:`-lijst met `target: {kind: Deployment, name: backend}`, in de vorm van een **strategic-merge-patch** die de env-var `APP_JWT_SECRET` overschrijft naar `value: ""` en `valueFrom: null`.
- Gebruik expliciet **geen** positionele JSON6902-patch op de env-index (`/spec/template/spec/containers/0/env/10`): die index schuift bij elke nieuwe env-var in de base. Voeg deze patch dus toe als een aparte entry, niet in de bestaande JSON6902-patchlijst van de backend-Deployment (die twee patch-vormen zijn niet te mengen binnen één entry).
- Werk de commentaarkop van het bestand bij: neem onder "Verschillen met de productie-overlay" een regel op dat previews geen productie-JWT-sleutel krijgen maar een ephemeral sleutel per pod.

Buiten scope:

- `deploy/base/backend-deployment.yaml`, `deploy/base/sealed-secret-api-keys.yaml` en `deploy/overlays/openshift/kustomization.yaml` blijven ongewijzigd.
- Geen backend-/Kotlin-wijzigingen; `JwtService` en `JwtAuthFilter` blijven zoals ze zijn.
- Geen nieuw (Sealed)Secret, geen her-sealen, geen wijziging in `deploy/preview-ns-labeller/labeller.sh`.
- Het opsplitsen van de bredere gespiegelde secret-bundel (GITHUB_TOKEN, OPENSHIFT_API_TOKEN, NEON_API_KEY, TUNNEL_TOKEN, factory-credentials) hoort niet bij deze story; dat vergt her-sealen en werk in de robberts-infrastructure-repo en is een aparte vervolgstory.

## Acceptance criteria

- `kubectl kustomize deploy/overlays/preview` (of `kustomize build`) slaagt zonder fouten en bevat in de gerenderde backend-Deployment **geen** `secretKeyRef` met `key: JWT_SECRET` meer; de env-var `APP_JWT_SECRET` is aanwezig met een lege waarde.
- `kubectl kustomize deploy/overlays/openshift` bevat die `secretKeyRef` naar `key: JWT_SECRET` onveranderd wél — productie blijft de vaste sleutel gebruiken.
- Alle overige env-vars in de gerenderde preview-Deployment (o.a. `PNF_DATABASE_URL`, `SPRING_DATASOURCE_URL`, `PNF_OPENAI_API_KEY`, `PNF_TAVILY_API_KEY`, `PNF_ELEVENLABS_API_KEY`, `JAVA_TOOL_OPTIONS`, `TZ`, `APP_DATA_DIR`) blijven inhoudelijk ongewijzigd aanwezig; alleen de volgorde in de gerenderde lijst mag verschuiven.
- De bestaande preview-patches blijven werken: de gerenderde preview bevat nog steeds `emptyDir` voor het `data`-volume en `strategy.type: RollingUpdate`, en bevat geen SealedSecret, Routes, PVC, cloudflared of preview-router.
- De diff raakt uitsluitend `deploy/overlays/preview/kustomization.yaml` (plus de standaard worklog).

## Aannames

- Een preview-backend die bij het opstarten een random ephemeral JWT-sleutel genereert is het gewenste gedrag: previews draaien met `replicas: 1`, tokens worden ongeldig bij pod-herstart en de e2e-runner logt per run opnieuw in via `POST /api/auth/login` (`e2e/runner.js:117`), dus er is geen functionele regressie in de preview-flow.
- De backend start normaal op met een lege `APP_JWT_SECRET`: `application.properties` gebruikt `app.jwt.secret=${APP_JWT_SECRET:}` en `JwtService` valt terug op een 64-byte random sleutel met een waarschuwing in de log.
- Tokens uit een preview zijn na deze wijziging niet meer geldig op productie en omgekeerd; dat is precies het doel en vraagt geen extra migratie.
- De Secret `newsfeed-api-keys` blijft via de reflector naar `pnf-*`-namespaces gespiegeld worden (nodig voor o.a. `PNF_DATABASE_URL`); alleen de koppeling van de JWT-sleutel aan de preview-Deployment vervalt.
- De env-var wordt bewust leeggezet in plaats van volledig verwijderd (`$patch: delete`), zodat in de gerenderde manifest zichtbaar blijft dát de sleutel bewust leeg is.

## Eindsamenvatting

Ik heb `.task.md`, de worklog en de diff gelezen. De story is compleet (development + review + test afgerond); hier is de eindsamenvatting.

---

# SF-1542 — PR-previews krijgen een eigen JWT-sleutel in plaats van de productiesleutel

## Aanleiding
Elke PR-preview kreeg dezelfde JWT-ondertekensleutel mee als productie. Code op een willekeurige PR-branch kon daarmee een 30 dagen geldig inlogtoken maken dat óók op de echte productieomgeving werkte — inclusief admin-rechten.

## Wat is gebouwd
Eén wijziging, in één bestand: `deploy/overlays/preview/kustomization.yaml`.

- Een nieuwe, losstaande patch-entry op de backend-Deployment zet in previews de env-var `APP_JWT_SECRET` op een lege waarde en laat de verwijzing naar het productiegeheim (`newsfeed-api-keys` / `JWT_SECRET`) vervallen.
- De backend genereert bij een lege waarde zelf een willekeurige sleutel bij het opstarten (bestaand gedrag in `JwtService`). Elke preview-pod heeft dus zijn eigen, tijdelijke sleutel.
- De commentaarkop van het bestand is bijgewerkt zodat dit verschil met productie gedocumenteerd staat.

Gevolg: een token uit een preview werkt niet meer op productie en omgekeerd. Previews blijven gewoon werken; tokens vervallen wel als de pod herstart, wat prima is omdat previews wegwerp zijn en de e2e-runner per run opnieuw inlogt.

## Gemaakte keuzes
- **Strategic-merge-patch in een aparte entry**, bewust géén positionele JSON6902-patch op de env-index: die index schuift mee zodra er een env-var in de base bijkomt. Kustomize staat het mengen van beide patch-vormen binnen één entry niet toe, vandaar een losse entry.
- **Leegzetten in plaats van verwijderen** van de env-var, zodat in het gerenderde manifest zichtbaar blijft dát de sleutel bewust leeg is.
- **Geen nieuw geheim, geen her-sealen, geen code-wijziging** — de backend had de fallback al.

## Wat is getest
- `kubectl kustomize deploy/overlays/preview` slaagt; de gerenderde diff t.o.v. `main` bestaat uit exact twee regels: `APP_JWT_SECRET` met lege waarde erbij, de `secretKeyRef` naar `JWT_SECRET` eraf. Verder nul verschil — alle overige env-vars, `emptyDir`, `RollingUpdate` en `replicas: 1` ongewijzigd.
- `kubectl kustomize deploy/overlays/openshift` is byte-identiek aan `main`: **productie blijft de vaste sleutel gebruiken**.
- Live geverifieerd op preview `pnf-pr-196`: de pod draait `1/1 Running` met 0 restarts, het productiegeheim zit niet meer in de pod, en het backend-log toont de verwachte waarschuwing dat er een ephemeral sleutel is gegenereerd.
- Auth werkt end-to-end: zonder token → 403, registreren → geldig JWT, datzelfde token → 200 op `/api/feed`. UI-login via Playwright slaagt en landt op het Feed-scherm (screenshots vastgelegd).
- Backend-build als vangnet: `mvn clean verify` groen (80 unit-tests + 65 e2e-tests). Eén eerdere run gaf een infrastructuur-flake op de test-database; herhaling was groen.
- Het testaccount is na afloop opgeruimd; productie is niet aangeraakt.

## Bewust niet gedaan
- **Het opsplitsen van de bredere gespiegelde secret-bundel** (GITHUB_TOKEN, OPENSHIFT_API_TOKEN, NEON_API_KEY, TUNNEL_TOKEN, factory-credentials) die previews nog steeds meekrijgen. Dat vraagt her-sealen en werk in de infrastructuur-repo — **aanbeveling: als vervolgstory oppakken**, want dit is de resterende helft van het oorspronkelijke auditpunt.
- Geen geautomatiseerde test toegevoegd: de wijziging is puur declaratieve kustomize-configuratie zonder code; het bewijs is de manifest-vergelijking plus de live preview-verificatie.
- Twee bekende, pre-existing afwijkingen in hetzelfde bestand bleven staan (buiten scope): een verouderde verwijzing naar `deploy/applicationset.yaml` in de commentaarkop, en `Route/reader` die ook in de preview-render zit.

## Aandachtspunt voor later
De patch adresseert de container op naam (`backend`). Wordt die container in de base ooit hernoemd, dan faalt de patch niet zichtbaar maar voegt hij stilzwijgend een lege container toe. Bij een rename in de base dus even hercontroleren.

---
