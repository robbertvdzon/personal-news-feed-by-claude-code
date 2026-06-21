# syntax=docker/dockerfile:1.7

# ─── Build stage ─────────────────────────────────────────────────────
# Cirruslabs publiceert Flutter-images per versie. Pinnen we op 3.35 om
# bij de versie in de devcontainer te blijven.
FROM ghcr.io/cirruslabs/flutter:3.35.0 AS build
WORKDIR /src

# Pub-cache hergebruiken tussen builds.
COPY pubspec.yaml pubspec.lock ./
RUN --mount=type=cache,target=/root/.pub-cache flutter pub get

COPY . .

# Build-metadata wordt door CI als build-arg meegegeven (zie
# .github/workflows/build-images.yml). Bij lokale builds vallen ze terug
# op een placeholder. Worden via String.fromEnvironment in Dart gelezen.
ARG BUILD_SHA=unknown
ARG BUILD_TIME=unknown

# API_BASE_URL leeg: API-calls worden relatief (`/api/...`), nginx
# proxy't ze door naar de backend-service in-cluster.
#
# --pwa-strategy=none: GÉÉN service worker registreren (net als
# frontend-reader). Een SW cachet de app-shell en blijft bij terugkerende
# bezoekers de OUDE build serveren — dan werkt bv. een vers gedeelde
# deeplink-URL (/feed/<id>, /rss/<id>) niet tot de SW toevallig update.
# nginx ruimt bestaande SW's op via een kill-switch (zie nginx.conf).
# --no-tree-shake-icons: behoud de volledige MaterialIcons-font; tree-shaking
# liet sommige glyphs (o.a. done_all) blanco renderen op web.
RUN flutter build web --release \
      --pwa-strategy=none \
      --no-tree-shake-icons \
      --dart-define=API_BASE_URL= \
      --dart-define=BUILD_SHA=${BUILD_SHA} \
      --dart-define=BUILD_TIME=${BUILD_TIME}

# Cache-busting via versie in de bestandsnaam. Flutter geeft 'main.dart.js'
# een VASTE naam; samen met immutable-caching zou een browser eindeloos een
# oude bundle kunnen serveren. We hashen 'm → main.<hash>.js (nieuwe naam per
# build) en updaten de referenties in flutter_bootstrap.js/flutter.js.
# index.html + flutter_bootstrap.js blijven no-cache (zie nginx), dus de
# nieuwe naam wordt altijd meteen opgepikt.
RUN cd build/web \
 && HASH=$(sha256sum main.dart.js | cut -c1-12) \
 && mv main.dart.js "main.${HASH}.js" \
 && sed -i "s/main\.dart\.js/main.${HASH}.js/g" flutter_bootstrap.js flutter.js \
 && echo "Hashed bundle -> main.${HASH}.js" \
 && test -z "$(grep -rl 'main\.dart\.js' . || true)" || (echo "ERROR: dangling main.dart.js refs" && exit 1)

# Font-versie: append ?v=<hash> aan de MaterialIcons-font in FontManifest.json
# (alleen als de font bestaat) zodat een gewijzigde glyph-set altijd vers
# binnenkomt bij bestaande bezoekers.
RUN cd build/web \
 && f=assets/fonts/MaterialIcons-Regular.otf \
 && if [ -f "$f" ]; then \
      MIH=$(sha256sum "$f" | cut -c1-8); \
      sed -i "s#fonts/MaterialIcons-Regular.otf#fonts/MaterialIcons-Regular.otf?v=${MIH}#g" assets/FontManifest.json; \
      echo "Versioned font -> ?v=${MIH}"; \
    fi

# ─── Runtime stage ───────────────────────────────────────────────────
# nginxinc/nginx-unprivileged is een variant van het officiële nginx-image
# die out-of-the-box als non-root user draait:
#   * Listent op poort 8080 ipv 80
#   * PID-file in /tmp ipv /run
#   * Geen chown/chmod tricks meer nodig
# Past perfect bij OpenShift's restricted-v2 SCC (random UID, gid=0).
FROM nginxinc/nginx-unprivileged:1.27-alpine AS runtime

# nginx-unprivileged draait als UID 101, group 0. OpenShift override't de
# user toch met een random UID maar houdt 'm in group 0 — onze configs +
# html-dir moeten daarom group-writable zijn.
USER 0
RUN chgrp -R 0 /etc/nginx /usr/share/nginx/html /var/cache/nginx \
 && chmod -R g+rwX /etc/nginx /usr/share/nginx/html /var/cache/nginx

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/build/web /usr/share/nginx/html

USER 101

EXPOSE 8080
