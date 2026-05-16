# syntax=docker/dockerfile:1.7

# ─── Build stage ─────────────────────────────────────────────────────
# Cirruslabs flutter-image bevat Android SDK + Flutter, dus we kunnen
# zowel `flutter build web` als `flutter build apk` doen in dezelfde
# stage. Pinnen op 3.35 — zelfde versie als de PNF-frontend.
FROM ghcr.io/cirruslabs/flutter:3.35.0 AS build
WORKDIR /src

COPY pubspec.yaml ./
# pubspec.lock niet committen-vereist; pub get genereert 'm.
# GEEN cache-mount op /root/.pub-cache: de packages moeten ook in de
# layer staan zodat de latere apk-build ze kan lezen. Een cache-mount
# zou de pub-cache verbergen achter een (mogelijk lege) volume.
RUN flutter pub get

# Scaffolding-bestanden (android/, web/-defaults, etc.) genereren als
# ze niet in git zitten. Idempotent: bestaande lib/main.dart blijft.
RUN flutter create --platforms=android,web --org com.vdzonsoftware --project-name pnf_dashboard . || true

# Launcher-icoon: voorgegenereerde mipmaps + adaptive-icon XML rechtstreeks
# kopiëren over de defaults die `flutter create` plaatste. Eerdere poging
# met flutter_launcher_icons leverde lege icons op in de APK — keten van
# Gradle-resource-merging gaf de defaults voorrang. Pre-baken is bombproof.
COPY icons/res/ android/app/src/main/res/

COPY analysis_options.yaml ./
COPY lib/ ./lib/
COPY web/index.html ./web/

ARG API_BASE_URL=
ARG BUILD_SHA=unknown
ARG BUILD_TIMESTAMP=unknown

# Web build — relatieve API-calls (nginx proxy't /api/* in deze image).
RUN flutter build web --release \
      --dart-define=API_BASE_URL=${API_BASE_URL} \
      --dart-define=BUILD_SHA=${BUILD_SHA} \
      --dart-define=BUILD_TIMESTAMP=${BUILD_TIMESTAMP}

# APK build — debug-signed (default Flutter debug-key). Voldoende voor
# 'install via Unknown Sources' op je eigen toestel.
# Geen pub-cache mount hier: de layer bevat al alles uit `flutter pub get`
# (zonder cache-mount); een mount zou de pub-cache-dir overlayen met een
# lege volume → 'No such file or directory' op alle packages.
# Gradle-deps via /root/.gradle + /src/android/.gradle cache mounts blijven
# wel zinvol (~500MB Android deps).
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/src/android/.gradle \
    flutter build apk --debug \
      --dart-define=API_BASE_URL=${API_BASE_URL:-https://status.vdzonsoftware.nl} \
      --dart-define=BUILD_SHA=${BUILD_SHA} \
      --dart-define=BUILD_TIMESTAMP=${BUILD_TIMESTAMP}

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
