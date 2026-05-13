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
RUN flutter build web --release \
      --dart-define=API_BASE_URL= \
      --dart-define=BUILD_SHA=${BUILD_SHA} \
      --dart-define=BUILD_TIME=${BUILD_TIME}

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
