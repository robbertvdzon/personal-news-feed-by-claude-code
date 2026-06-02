.PHONY: run serve serve-public build-apk build-web icons

# Production-URL waar de backend bereikbaar is. De APK praat rechtstreeks
# met deze hostname (Cloudflare Tunnel → cluster).
PROD_API ?= https://news.vdzonsoftware.nl

# Lokaal: web-app op poort 3100 (zodat 'ie niet botst met de hoofd-app op 3000).
run: serve

serve:
	flutter run -d web-server --web-port 3100 \
	  --dart-define=API_BASE_URL=http://localhost:8080

# Web extern bereikbaar (LAN / telefoon) tegen de prod-backend.
PUBLIC_API ?= $(PROD_API)
serve-public:
	flutter run -d web-server --web-port 3100 --web-hostname 0.0.0.0 \
	  --dart-define=API_BASE_URL=$(PUBLIC_API)

# Productie-web-build (zelfde define als Docker).
build-web:
	flutter build web --release --dart-define=API_BASE_URL=

# Release-APK die met de cluster-backend praat.
# Output: build/app/outputs/flutter-apk/app-release.apk
build-apk:
	flutter build apk --release --dart-define=API_BASE_URL=$(PROD_API)

# Launcher-icons (her)genereren uit assets/app_icon.png.
icons:
	flutter pub run flutter_launcher_icons
