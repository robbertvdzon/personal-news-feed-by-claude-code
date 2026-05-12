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

# API_BASE_URL leeg: API-calls worden relatief (`/api/...`), nginx
# proxy't ze door naar de backend-service in-cluster. Voordeel: geen
# rebuild als het domein verandert.
RUN flutter build web --release --dart-define=API_BASE_URL=

# ─── Runtime stage ───────────────────────────────────────────────────
# nginx-alpine + de static build-output + onze proxy-config.
FROM nginx:1.27-alpine AS runtime

# OpenShift mount /var/cache/nginx etc. read-only en draait als non-root.
# Default nginx-image schrijft naar /var/cache, /var/run, /var/log; we
# rewriten conf zodat alles in /tmp gaat en geven group write op de
# nginx-config + html-dir.
RUN sed -i 's,/var/run/nginx.pid,/tmp/nginx.pid,' /etc/nginx/nginx.conf \
 && chgrp -R 0 /var/cache/nginx /var/log/nginx /etc/nginx /usr/share/nginx/html \
 && chmod -R g+rwX /var/cache/nginx /var/log/nginx /etc/nginx /usr/share/nginx/html

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/build/web /usr/share/nginx/html

# OpenShift's SCC overschrijft USER toch met random UID; we zetten 'm
# expliciet op een non-root user voor lokale `docker run`.
USER 101

EXPOSE 8080

# Foreground draaien (default cmd doet dat al).
