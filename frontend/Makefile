.PHONY: run serve serve-ext serve-public build-apk build-apk-ext

# Production-URL waar de backend bereikbaar is. De Android-app praat
# rechtstreeks met deze hostname (Cloudflare Tunnel → cluster). Override
# bij command-line invocation indien nodig:
#   make build-apk-ext PROD_API=https://andere-hostname.nl
PROD_API ?= https://news.vdzonsoftware.nl

# Lokaal: web-app op poort 3000 (alleen localhost), backend op localhost:8080.
# Default doel — gebruik dit als de browser op dezelfde Mac draait.
run: serve-ext

serve: serve-ext

serve-ext:
	flutter run -d web-server --web-port 3000 \
	  --dart-define=API_BASE_URL=http://localhost:8080

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
	  --dart-define=API_BASE_URL=$(PUBLIC_API)

# APK build voor productie: praat met de cluster-backend via Cloudflare.
# Output: build/app/outputs/flutter-apk/app-release.apk
build-apk-ext:
	flutter build apk --release \
	  --dart-define=API_BASE_URL=$(PROD_API)

# APK met default API_BASE_URL (http://localhost:8080 — alleen handig
# voor android-emulator op dezelfde machine).
build-apk:
	flutter build apk --release
