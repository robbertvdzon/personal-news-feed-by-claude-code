.PHONY: run serve serve-ext serve-public build-apk build-apk-ext build-web

# Production-URL waar de backend bereikbaar is. De Android-app praat
# rechtstreeks met deze hostname (Cloudflare Tunnel → cluster). Override
# bij command-line invocation indien nodig:
#   make build-apk-ext PROD_API=https://andere-hostname.nl
PROD_API ?= https://news.vdzonsoftware.nl

# Build-metadata. Bij CI komen die als build-args binnen; lokaal pakken
# we wat we uit git kunnen lezen (short-SHA + UTC-now). Geef Dart op de
# command-line dezelfde waarden mee zodat de app weet welke build dit is.
BUILD_SHA ?= $(shell git rev-parse --short HEAD 2>/dev/null || echo unknown)
BUILD_TIME ?= $(shell date -u +%FT%TZ)
BUILD_DEFINES = --dart-define=BUILD_SHA=$(BUILD_SHA) --dart-define=BUILD_TIME=$(BUILD_TIME)

# Lokaal: web-app op poort 3000 (alleen localhost), backend op localhost:8080.
# Default doel — gebruik dit als de browser op dezelfde Mac draait.
run: serve-ext

serve: serve-ext

serve-ext:
	flutter run -d web-server --web-port 3000 \
	  --dart-define=API_BASE_URL=http://localhost:8080 \
	  $(BUILD_DEFINES)

# Web-app extern bereikbaar maken (LAN of via port-forward).
#
# Flutter's `web-server` device luistert standaard alléén op localhost; met
# --web-hostname 0.0.0.0 luistert hij op alle interfaces. Een externe
# gebruiker bereikt de app op http://<extern-ip>:<port>.
#
# Standaard pakt deze target de production-URL — handig als je vanaf je
# telefoon (op LAN of mobile data) wilt testen tegen de echte backend.
# Override met PUBLIC_API als je een ander backend-adres wilt gebruiken:
#   make serve-public PUBLIC_API=http://192.168.1.50:8080
PUBLIC_API ?= $(PROD_API)
serve-public:
	flutter run -d web-server --web-port 3000 --web-hostname 0.0.0.0 \
	  --dart-define=API_BASE_URL=$(PUBLIC_API) \
	  $(BUILD_DEFINES)

# Productie-web-build (zelfde defines als Docker, handig voor lokale check).
build-web:
	flutter build web --release \
	  --dart-define=API_BASE_URL= \
	  $(BUILD_DEFINES)

# APK build voor productie: praat met de cluster-backend via Cloudflare.
# Output: build/app/outputs/flutter-apk/app-release.apk
build-apk-ext:
	flutter build apk --release \
	  --dart-define=API_BASE_URL=$(PROD_API) \
	  $(BUILD_DEFINES)

# APK met default API_BASE_URL (http://localhost:8080 — alleen handig
# voor android-emulator op dezelfde machine).
build-apk:
	flutter build apk --release \
	  $(BUILD_DEFINES)
