# syntax=docker/dockerfile:1.7

# ─── Build stage ─────────────────────────────────────────────────────
# Cirruslabs flutter-image bevat Android SDK + Flutter, dus we kunnen
# zowel `flutter build web` als `flutter build apk` doen in dezelfde
# stage. Pinnen op 3.35 — zelfde versie als de PNF-frontend.
FROM ghcr.io/cirruslabs/flutter:3.35.0 AS build
WORKDIR /src

COPY pubspec.yaml ./
# pubspec.lock niet committen-vereist; pub get genereert 'm.
RUN --mount=type=cache,target=/root/.pub-cache flutter pub get

# Scaffolding-bestanden (android/, web/-defaults, etc.) genereren als
# ze niet in git zitten. Idempotent: bestaande lib/main.dart blijft.
RUN flutter create --platforms=android,web --org com.vdzonsoftware --project-name pnf_dashboard . || true

COPY analysis_options.yaml ./
COPY lib/ ./lib/
COPY web/index.html ./web/

ARG API_BASE_URL=
ARG BUILD_SHA=unknown

# Web build — relatieve API-calls (nginx proxy't /api/* in deze image).
RUN flutter build web --release \
      --dart-define=API_BASE_URL=${API_BASE_URL} \
      --dart-define=BUILD_SHA=${BUILD_SHA}

# APK build — debug-signed (default Flutter debug-key). Voldoende voor
# 'install via Unknown Sources' op je eigen toestel.
# LET OP: doet 'flutter build apk' ook iets met build/web? Sommige Flutter-
# versies regenereren shared artifacts. We doen 'm dus VOOR de
# hashing-stap, zodat de hashing 't laatste woord heeft.
RUN flutter build apk --debug \
      --dart-define=API_BASE_URL=${API_BASE_URL:-https://status.vdzonsoftware.nl} \
      --dart-define=BUILD_SHA=${BUILD_SHA}

# Content-hash main.dart.js MOET de laatste build-stap zijn — anders
# regenereren latere flutter-commands main.dart.js en is de rename weg.
# Browser-/CDN-cache mag de gehashte file dan immutable bewaren omdat
# de naam per build verandert.
RUN cd build/web \
    && HASH=$(sha1sum main.dart.js | cut -c1-12) \
    && mv main.dart.js "main.${HASH}.dart.js" \
    && sed -i "s|main\.dart\.js|main.${HASH}.dart.js|g" flutter_bootstrap.js \
    && sed -i "s|main\.dart\.js|main.${HASH}.dart.js|g" flutter_service_worker.js \
    && echo "main.dart.js hashed to main.${HASH}.dart.js" \
    && ls -la main*.dart.js

# ─── Runtime stage ───────────────────────────────────────────────────
FROM nginxinc/nginx-unprivileged:1.27-alpine AS runtime
USER 0
RUN chgrp -R 0 /etc/nginx /usr/share/nginx/html /var/cache/nginx \
 && chmod -R g+rwX /etc/nginx /usr/share/nginx/html /var/cache/nginx \
 && mkdir -p /usr/share/nginx/html/download \
 && chgrp -R 0 /usr/share/nginx/html/download \
 && chmod -R g+rwX /usr/share/nginx/html/download

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/build/web /usr/share/nginx/html
COPY --from=build /src/build/app/outputs/flutter-apk/app-debug.apk \
                  /usr/share/nginx/html/download/dashboard.apk

USER 101
EXPOSE 8080
