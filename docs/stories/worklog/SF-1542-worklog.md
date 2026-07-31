# SF-1542 - Worklog

Story-context bij eerste pickup:
Preview-overlay: APP_JWT_SECRET leegzetten via strategic-merge-patch

Wijzig UITSLUITEND deploy/overlays/preview/kustomization.yaml.

1) Voeg onderaan de bestaande `patches:`-lijst een NIEUWE, aparte entry toe met `target: {kind: Deployment, name: backend}` en een STRATEGIC-MERGE-patch (volledig Deployment-fragment: apiVersion apps/v1, kind Deployment, metadata.name backend). Het fragment adresseert in spec.template.spec.containers de container met `name: backend` en zet daarin in de env-lijst de entry `name: APP_JWT_SECRET` op `value: ""` met `valueFrom: null`, zodat de secretKeyRef naar newsfeed-api-keys/JWT_SECRET vervalt maar de env-var zichtbaar leeg aanwezig blijft.

2) Gebruik expliciet GEEN positionele JSON6902-patch op /spec/template/spec/containers/0/env/10 (index schuift mee met nieuwe env-vars in de base), en voeg de patch NIET toe aan de bestaande JSON6902-entry op Deployment/backend (regels ~102-115) - kustomize mengt JSON6902 en strategic merge niet binnen één entry. Het moet dus een losse entry zijn.

3) Werk de commentaarkop van het bestand bij: voeg onder 'Verschillen met de productie-overlay' een regel toe dat previews geen productie-JWT-sleutel krijgen maar per pod een ephemeral sleutel (de backend genereert die zelf bij opstarten). Laat de verouderde verwijzing naar deploy/applicationset.yaml staan (buiten scope).

Raak NIET aan: deploy/base/backend-deployment.yaml, deploy/base/sealed-secret-api-keys.yaml, deploy/overlays/openshift/kustomization.yaml, deploy/preview-ns-labeller/labeller.sh, en alle backend-/Kotlin-code (JwtService, JwtAuthFilter blijven ongewijzigd). Geen nieuw (Sealed)Secret, geen her-sealen.

Verifieer zelf voor je klaar bent (kubectl is beschikbaar):
- `kubectl kustomize deploy/overlays/preview` slaagt zonder fouten; in de gerenderde backend-Deployment staat GEEN secretKeyRef met key JWT_SECRET meer, staat APP_JWT_SECRET precies EENMAAL met lege waarde en zonder valueFrom, en is er precies ÉÉN container `backend` (let op: een verkeerde merge-key voegt stilzwijgend een extra container of env-entry toe).
- Alle overige env-vars (PNF_DATABASE_URL, SPRING_DATASOURCE_URL, PNF_OPENAI_API_KEY, PNF_TAVILY_API_KEY, PNF_ELEVENLABS_API_KEY, JAVA_TOOL_OPTIONS, TZ, APP_DATA_DIR) blijven inhoudelijk ongewijzigd aanwezig; alleen volgorde mag verschuiven.
- De bestaande preview-patches blijven werken: emptyDir voor het data-volume, strategy.type RollingUpdate, en geen SealedSecret/Routes/PVC/cloudflared/preview-router in de render.
- `kubectl kustomize deploy/overlays/openshift` bevat de secretKeyRef naar key JWT_SECRET onveranderd WEL.
- `git status`/`git diff` toont alleen deploy/overlays/preview/kustomization.yaml (plus de standaard worklog).

Sluit af met een eigen reviewronde op de diff (correctheid van de merge-keys, geen scope-creep, commentaarkop klopt) en werk de worklog bij.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `deploy/overlays/preview/kustomization.yaml`: nieuwe, losse patch-entry met
  `target: {kind: Deployment, name: backend}` en een strategic-merge-patch
  (apiVersion apps/v1 / kind Deployment / metadata.name backend). Het fragment
  adresseert de container via merge-key `name: backend` en de env-entry via
  merge-key `name: APP_JWT_SECRET`, en zet daarop `value: ""` + `valueFrom: null`.
  Bewust geen positionele JSON6902-patch op de env-index en bewust niet in de
  bestaande JSON6902-entry (kustomize mengt beide patch-vormen niet binnen één
  entry, en de index schuift mee met nieuwe env-vars in de base).
- Commentaarkop uitgebreid onder "Verschillen met de productie-overlay" met een
  regel dat previews geen productie-JWT-sleutel krijgen maar per pod een
  ephemeral sleutel (backend genereert die zelf bij opstarten). De verouderde
  verwijzing naar deploy/applicationset.yaml is bewust ongewijzigd gelaten
  (buiten scope).

Verificatie:
- `kubectl kustomize deploy/overlays/preview` slaagt (alleen de bestaande
  commonLabels-deprecation-warning). De gerenderde diff t.o.v. vóór de wijziging
  bestaat uit exact twee hunks: `APP_JWT_SECRET` met `value: ""` erbij en de
  oude entry met `secretKeyRef` naar `key: JWT_SECRET` eraf. Geen andere
  wijziging in de render.
- Precies één container `backend` in de gerenderde preview-Deployment, en
  `APP_JWT_SECRET` staat er precies één keer in, zonder `valueFrom`. Alle
  overige env-vars (PNF_DATABASE_URL, SPRING_DATASOURCE_URL, PNF_OPENAI_API_KEY,
  PNF_TAVILY_API_KEY, PNF_ELEVENLABS_API_KEY, JAVA_TOOL_OPTIONS, TZ,
  APP_DATA_DIR) zijn inhoudelijk ongewijzigd aanwezig.
- Bestaande preview-patches werken nog: `emptyDir` voor het data-volume en
  `strategy.type: RollingUpdate`; geen SealedSecret, geen frontend/backend-debug
  Route, geen PVC, geen cloudflared en geen preview-router in de render.
  (De reeds bestaande `Route/reader` in de preview-render is ongewijzigd
  aanwezig — dat was vóór deze wijziging ook zo en valt buiten deze story.)
- `kubectl kustomize deploy/overlays/openshift` bevat de `secretKeyRef` naar
  `key: JWT_SECRET` onveranderd wél; productie blijft de vaste sleutel gebruiken.
- Vangnet `mvn -B --no-transfer-progress clean verify`
  (newsfeedbackend/newsfeedbackend): BUILD SUCCESS, 80 unit-tests + 65 e2e-tests,
  0 failures / 0 errors. De eerste run gaf 10 errors in AdminE2eTest door een
  `SocketTimeoutException` op de Testcontainers-Postgres (infrastructuur-flake,
  geen relatie met deze YAML-only wijziging); een volledige herhaling van
  hetzelfde commando was groen.
- Geen (unit)tests toegevoegd: de wijziging is puur declaratieve
  kustomize-configuratie zonder Kotlin-/Dart-code; het bewijs is de gerenderde
  manifest-vergelijking hierboven.
- `git status` toont alleen deploy/overlays/preview/kustomization.yaml plus deze
  worklog.
