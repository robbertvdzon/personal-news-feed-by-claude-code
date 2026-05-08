.PHONY: run serve serve-ext build-apk build-apk-ext

# Lokaal: web-app op poort 3000, backend op localhost:8080
run: serve-ext

serve: serve-ext

serve-ext:
	flutter run -d web-server --web-port 3000 \
	  --dart-define=API_BASE_URL=http://localhost:8080

# APK build voor productie (extern adres)
build-apk-ext:
	flutter build apk --release \
	  --dart-define=API_BASE_URL=http://217.120.100.76:19283

build-apk:
	flutter build apk --release
