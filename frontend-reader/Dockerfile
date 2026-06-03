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
# --no-tree-shake-icons: behoud de volledige MaterialIcons-font. Tree-shaking
# liet sommige glyphs (o.a. done_all) blanco renderen op web; de extra
# font-grootte cachen we immutable, dus dat is een eenmalige download.
RUN flutter build web --release --pwa-strategy=none --no-tree-shake-icons --dart-define=API_BASE_URL=

# Content-hash de app-bundle: Flutter geeft 'main.dart.js' een vaste naam,
# waardoor een CDN (Cloudflare) een oude versie eindeloos kan blijven
# serveren. Door 'm te hernoemen naar main.<hash>.js krijgt elke build een
# nieuwe URL → CDN/browser pakken 'm automatisch vers op, zonder purge.
# De enige verwijzing zit in flutter_bootstrap.js (mainJsPath); die werken
# we bij. flutter_bootstrap.js + index.html blijven no-cache (zie nginx),
# dus de nieuwe hash-naam wordt altijd meteen opgepikt.
RUN cd build/web \
 && HASH=$(sha256sum main.dart.js | cut -c1-12) \
 && mv main.dart.js "main.${HASH}.js" \
 && sed -i "s/main\.dart\.js/main.${HASH}.js/g" flutter_bootstrap.js flutter.js \
 && echo "Hashed bundle -> main.${HASH}.js" \
 && test -z "$(grep -rl 'main\.dart\.js' . || true)" || (echo "ERROR: dangling main.dart.js refs" && exit 1)

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
