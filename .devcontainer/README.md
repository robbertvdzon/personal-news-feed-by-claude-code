# Devcontainer

Een geheel-in-één gecontaineriseerde dev-omgeving voor de Personal News Feed monorepo. Bevat alle tooling die nodig is om backend en frontend te bouwen, en Claude Code om met de codebase te werken — zonder iets op je laptop te installeren.

Bedoeld voor:
- **Claude Code** in een afgeschermde omgeving draaien (filesystem-acties blijven binnen de container).
- Reproduceerbaar bouwen op meerdere machines (laptop, desktop, Codespaces).
- IntelliJ blijft op je host, deze container draait er parallel naast.

## Wat zit erin?

| Component | Versie |
|---|---|
| Ubuntu | 22.04 |
| OpenJDK | 21 |
| Maven | apt-default (≥ 3.8) |
| Kotlin | komt mee via Maven (2.2.21) |
| Flutter | 3.35.0 |
| Dart | komt mee met Flutter (3.9) |
| Android SDK | platform-34, build-tools 34.0.0 |
| Node | 20.x |
| Claude Code | latest (`@anthropic-ai/claude-code`, npm) |
| Extra | git, gh, jq, ripgrep, fd, zsh |

iOS-builds gaan **niet** in deze container — daar heeft een Mac met Xcode voor nodig. Web en Android werken wel.

### Architectuur

De image draait altijd als `linux/amd64`, ook op een Apple Silicon-Mac. Reden: Flutter publiceert geen arm64 Linux-tarballs, dus om Flutter mee te krijgen moeten we het hele image x86_64 houden. Op M-series wordt dat door Docker Desktop / Rosetta geëmuleerd — performance-overhead is in praktijk ~20-30% (Rosetta is goed). Op een echte amd64-host (Linux/Intel) is het native.

## Vereisten op je host

- Docker Desktop (Mac/Win) of Docker Engine (Linux)
- Optioneel: [`@devcontainers/cli`](https://github.com/devcontainers/cli) voor een prettigere CLI-flow:
  ```bash
  npm install -g @devcontainers/cli
  ```

Op je host moeten deze paden bestaan, want we mounten ze door:

| Pad op host | Doel |
|---|---|
| `~/.gitconfig` | Git-naam/email + andere settings |
| `~/.ssh/` | SSH-keys voor `git push` naar GitHub |
| `~/.claude/` | Login-state van Claude Code (gedeeld met host-Claude) |

## Eerste keer bouwen

```bash
cd /pad/naar/personal-news-feed-by-claude-code

# Optie A: via devcontainer-CLI (aanbevolen — leest devcontainer.json compleet)
devcontainer up --workspace-folder .

# Optie B: alleen de image bouwen (zonder mount/run-config)
docker build -t newsfeed-dev .devcontainer/
```

De eerste build duurt **10–15 minuten** en download zo'n **5 GB** (JDK + Flutter SDK + Android SDK + Node + Claude Code). Daarna is alles cached. Bij volgende rebuilds verandert er meestal weinig en is een rebuild een minuut of minder.

## Container starten en betreden

Met de devcontainer-CLI:

```bash
# Start (idempotent — start de bestaande container of maakt 'm aan):
devcontainer up --workspace-folder .

# Open een interactieve shell erin:
devcontainer exec --workspace-folder . zsh
```

Je komt binnen op `/workspaces/personal-news-feed-by-claude-code` — exact dezelfde repo als op je host, alleen via een ander pad.

## Claude Code starten

Eenmaal in de container:

```bash
claude
```

Omdat we `~/.claude/` doormounten zit je login uit je host-installatie er al in. Geen herauthenticatie nodig.

## Tips voor gebruik

### IntelliJ-terminal koppelen aan de container

In IntelliJ → **Settings → Tools → Terminal → Shell path**:

```
devcontainer exec --workspace-folder /pad/naar/personal-news-feed-by-claude-code zsh
```

Elke nieuwe IntelliJ-terminal opent dan direct in de container. Je IntelliJ-editor blijft op de host — alleen het terminal-paneel zit in de container. Voor het werkt het als één omgeving.

### Backend in IntelliJ op host bereiken vanuit de container

Je IntelliJ draait Spring Boot op de host op `:8080`. Vanuit de container is dat **niet** `localhost:8080` (dat is de container zelf), maar:

```bash
curl http://host.docker.internal:8080/actuator/health
```

De env-var `$BACKEND_URL` is hierop voorgemerkt:

```bash
curl $BACKEND_URL/actuator/health
```

### Builds vanuit de container

Backend:
```bash
cd newsfeedbackend/newsfeedbackend
mvn -DskipTests package
```

Frontend web:
```bash
cd frontend
make serve-ext         # start op poort 3000, automatisch geforward
```

Android APK:
```bash
cd frontend
make build-apk-ext     # APK landt in frontend/build/app/outputs/flutter-apk/
```

De APK verschijnt op je **host** in dezelfde map (de bind-mount zorgt daarvoor). Geen `docker cp` nodig.

### Welke poorten geforward zijn

| Poort | Service | Waar te bereiken |
|---|---|---|
| 3000 | Flutter web (in container) | `http://localhost:3000` op je host |
| 8080 | Spring Boot (in container, indien je 'm daar runt) | `http://localhost:8080` op je host |

## Architectuur van de mounts

| Pad in container | Type | Waarom |
|---|---|---|
| `/workspaces/personal-news-feed-by-claude-code` | bind-mount jouw repo | Edits van host (IntelliJ) ↔ container synchroon |
| `~/.m2`, `~/.gradle`, `~/.pub-cache`, `~/.flutter` | named volumes | Dependency-caches, niet over bind-mount (= snelheid) |
| `newsfeedbackend/.../target/` | named volume | Maven build output (anders trek je honderden .class files via mount) |
| `frontend/.dart_tool/` | named volume | interne Flutter tooling, niet relevant voor host |
| `frontend/build/` | bind-mount (default) | APK landt zo automatisch op je host |
| `~/.gitconfig`, `~/.ssh` | bind-mount read-only | git push werkt met je bestaande GitHub-credentials |
| `~/.claude/` | bind-mount | Claude Code login gedeeld met host |

De named volumes zijn gemaakt zodat IO over de bind-mount (langzaam op macOS) tot een minimum beperkt blijft, terwijl de broncode én build-output (APK) wél op je host zichtbaar blijven.

## Beheer / probleemoplossing

### Versie-info in één blik

```bash
java -version && mvn -version && flutter --version && claude --version
```

(Wordt automatisch gedraaid bij de eerste create — zie de `postCreateCommand`.)

### Cache leeggooien

```bash
# Stop en verwijder container, behoud volumes (snelle reset):
docker rm -f $(docker ps -aq --filter "label=devcontainer.local_folder")

# Verwijder ook de named volumes (alle dependency-caches weg, image blijft):
docker volume rm newsfeed-m2 newsfeed-gradle newsfeed-pub-cache newsfeed-flutter-cache \
                newsfeed-mvn-target newsfeed-dart-tool

# Nuke en rebuild image:
docker rmi newsfeed-dev
docker build --no-cache -t newsfeed-dev .devcontainer/
```

### Permissies-issues op host na container-builds

`updateRemoteUserUID: true` mapt de container-user op je host-UID. Mocht je tóch ergens root-owned files zien op je host, dan is dat meestal omdat de container ooit als andere user heeft gedraaid; eenmalig op host fixen:

```bash
sudo chown -R $(id -u):$(id -g) /pad/naar/personal-news-feed-by-claude-code
```

### "host.docker.internal: name resolution failed"

Op Linux (geen Docker Desktop) heb je het soms nodig dat `host-gateway` expliciet wordt gemapt. Dat staat al in `runArgs` in `devcontainer.json`. Als het tóch niet werkt, hard-code je host-IP in `/etc/hosts` van de container.

## Bestanden in deze map

| Bestand | Inhoud |
|---|---|
| `Dockerfile` | Image-definitie (toolchain) |
| `devcontainer.json` | Bind-mounts, volumes, env, port-forwarding, post-create-script |
| `README.md` | Dit bestand |
