.PHONY: run serve serve-ext serve-public build-apk build-apk-ext

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
# Belangrijk: de browser van die externe gebruiker doet daarna een
# fetch() naar API_BASE_URL — dat moet dus óók een adres zijn dat hun
# browser kan bereiken (NIET localhost). Zet in API_BASE_URL het externe
# adres en poort waar de backend bereikbaar is. Voorbeeld als je extern
# IP 217.120.100.76 hebt en port-forward 19285→3000 + 19286→8080:
#
#   make serve-public PUBLIC_API=http://217.120.100.76:19286
#
# Standaard pakt deze target de waarde uit env-var PUBLIC_API; geef hem
# anders mee op de commandline.
PUBLIC_API ?= http://217.120.100.76:19286
serve-public:
	flutter run -d web-server --web-port 3000 --web-hostname 0.0.0.0 \
	  --dart-define=API_BASE_URL=$(PUBLIC_API)

# APK build voor productie (extern adres)
build-apk-ext:
	flutter build apk --release \
	  --dart-define=API_BASE_URL=http://217.120.100.76:19283

build-apk:
	flutter build apk --release
