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
# liet sommige glyphs (o.a. done_all) blanco renderen op web.
RUN flutter build web --release --pwa-strategy=none --no-tree-shake-icons --dart-define=API_BASE_URL=

# Cache-busting via versie in de URL. Flutter geeft 'main.dart.js' én de
# fonts een VASTE naam, waardoor CDN/browser een oude versie eindeloos
# kunnen blijven serveren (immutable). We geven ze een versie:
#   * main.dart.js  -> main.<hash>.js  (nieuwe bestandsnaam; ref in
#     flutter_bootstrap.js/flutter.js bijgewerkt)
#   * fonts         -> ?v=<hash>-query in FontManifest.json (zelfde bestand,
#     nieuwe URL) zodat een gewijzigde font-glyph altijd vers binnenkomt.
# index.html + flutter_bootstrap.js blijven no-cache (zie nginx), dus de
# nieuwe namen/queries worden altijd meteen opgepikt — geen purge nodig.
RUN cd build/web \
 && HASH=$(sha256sum main.dart.js | cut -c1-12) \
 && mv main.dart.js "main.${HASH}.js" \
 && sed -i "s/main\.dart\.js/main.${HASH}.js/g" flutter_bootstrap.js flutter.js \
 && echo "Hashed bundle -> main.${HASH}.js" \
 && test -z "$(grep -rl 'main\.dart\.js' . || true)" || (echo "ERROR: dangling main.dart.js refs" && exit 1)

# Font-versie: append ?v=<hash> aan de MaterialIcons-font in FontManifest.json
# (alleen als de font bestaat). Dit dwingt bestaande bezoekers met een oude,
# immutable-gecachte font om de nieuwe (volledige) font op te halen.
RUN cd build/web \
 && f=assets/fonts/MaterialIcons-Regular.otf \
 && if [ -f "$f" ]; then \
      MIH=$(sha256sum "$f" | cut -c1-8); \
      sed -i "s#fonts/MaterialIcons-Regular.otf#fonts/MaterialIcons-Regular.otf?v=${MIH}#g" assets/FontManifest.json; \
      echo "Versioned font -> ?v=${MIH}"; \
    fi

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
