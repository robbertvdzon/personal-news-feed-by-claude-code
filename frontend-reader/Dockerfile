# syntax=docker/dockerfile:1.7

# ─── Build stage ─────────────────────────────────────────────────────
FROM ghcr.io/cirruslabs/flutter:3.35.0 AS build
WORKDIR /src

COPY pubspec.yaml pubspec.lock ./
RUN --mount=type=cache,target=/root/.pub-cache flutter pub get

COPY . .

# API_BASE_URL leeg: API-calls worden relatief (`/api/...`), nginx
# proxy't ze door naar de backend-service in-cluster.
#
# --pwa-strategy=none: GÉÉN service worker registreren. Deze read-only app
# moet altijd vers laden; een SW zou oude builds blijven serveren ("gedoe").
# nginx ruimt bestaande SW's op via een kill-switch (zie nginx.conf).
RUN flutter build web --release --pwa-strategy=none --dart-define=API_BASE_URL=

# ─── Runtime stage ───────────────────────────────────────────────────
# nginx-unprivileged draait non-root op poort 8080 — past bij OpenShift's
# restricted-v2 SCC (random UID, gid=0).
FROM nginxinc/nginx-unprivileged:1.27-alpine AS runtime

USER 0
RUN chgrp -R 0 /etc/nginx /usr/share/nginx/html /var/cache/nginx \
 && chmod -R g+rwX /etc/nginx /usr/share/nginx/html /var/cache/nginx

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/build/web /usr/share/nginx/html

USER 101

EXPOSE 8080
