# Build the game (game/: TypeScript + PixiJS) with a pinned Node image; only the built files go into nginx.
FROM node:22-alpine@sha256:0a7108bf6c7bf5de370ffb1a3ed6be93d405b43ff159f681a8d18c0e2bc2e402 AS game
WORKDIR /build
COPY game/package.json game/package-lock.json ./
RUN npm ci --ignore-scripts --no-audit --no-fund
COPY game/ ./
# Review 4.4: half-size copies of the themes' @2x pictures (name@2x.png -> name.png) for screens that can't show the
# extra detail; the game asks for them below 1.5 device pixels per pixel. Made in public/ before the WebP step (so they
# get WebPs too) and before the build (so they are part of each theme's fingerprint); vips is pinned like cwebp below.
RUN apk add --no-cache vips-tools=8.18.2-r0 \
 && for f in $(find public/themes -type f -name '*@2x.png'); do \
      vips thumbnail "$f" "${f%@2x.png}.png" $(( ($(vipsheader -f width "$f") + 1) / 2 )) --height 100000 || exit 1; \
    done
# Review 4.4: the small pictures of each theme (buttons, ball, pegs, slots, sliders) packed into one texture atlas, @2x and
# half-size (atlas@2x.png/.json, atlas.png/.json): 2 requests instead of about 25 (scripts/theme-atlas.mjs, pure
# JavaScript). The single files stay: the game falls back to them if the atlas can't be loaded.
RUN node scripts/theme-atlas.mjs public
# Review 4.4: the big pictures also as WebP (a quarter of the PNG's size, no visible difference); the game asks for them
# when the browser can show them, and the PNGs stay for the rest. The atlases as lossless WebP (every pixel kept, smaller
# than the single PNGs together); their PNGs are only the encoder's input and are removed: a browser without WebP gets
# the single files, which weigh less.
# Review N12: made in public/ BEFORE the build, so the WebP bytes are part of each theme's fingerprint (?v=): a new
# encoder or new flags give new addresses, never a stale WebP cached for a year. The encoder is pinned to an exact
# Alpine package (the node image's Alpine 3.24); when Alpine replaces it the build fails here: pin the new version.
# One encoder per processor core; a failed one fails the build.
RUN apk add --no-cache libwebp-tools=1.6.0-r0 \
 && find public/themes -type f \( -name 'background*.png' -o -name 'logo*.png' -o -name 'item_plinko*.png' \
      -o -name 'item_exit*.png' -o -name 'item_result*.png' \) -print0 \
    | xargs -0 -n 1 -P "$(nproc)" sh -c 'cwebp -quiet -q 85 -alpha_q 90 -m 6 "$1" -o "${1%.png}.webp"' _ \
 && find public/themes -type f -name 'atlas*.png' -print0 \
    | xargs -0 -n 1 -P "$(nproc)" sh -c 'cwebp -quiet -lossless -z 9 "$1" -o "${1%.png}.webp" && rm "$1"' _
# Review 4.4: AVIF copies of the big pictures (about half the WebP's size again); the game prefers them where the browser
# shows AVIF (Chrome, Firefox, Safari 16+). An AVIF is kept only when it is smaller than the WebP, so it never costs a
# player more. Quality 60, alpha 80: looks like the WebP at 85. Not for the atlases: lossless AVIF is several times
# larger than lossless WebP (measured: 0.50 MB against 0.12 MB). Same rules as the WebP: made before the build (part of
# the fingerprint), the encoder pinned to an exact Alpine package; one encoder per processor core.
RUN apk add --no-cache libavif-apps=1.4.1-r0 \
 && find public/themes -type f -name '*.webp' ! -name 'atlas*' -print0 | xargs -0 -n 1 -P "$(nproc)" sh -c ' \
      avif="${1%.webp}.avif"; \
      avifenc -q 60 --qalpha 80 -s 6 -j 2 "${1%.webp}.png" "$avif" >/dev/null || exit 1; \
      [ "$(wc -c < "$avif")" -lt "$(wc -c < "$1")" ] || rm "$avif"' _
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.31.6-alpine@sha256:884e0b7f42f303641b0f4e78755aea647521dca0b89ca3be7992ad78b5c07434
# Security updates for the Alpine packages (e.g. zlib) published after the base image was built.
USER root
RUN apk upgrade --no-cache
COPY deploy/nginx/nginx.conf /etc/nginx/nginx.conf
COPY deploy/nginx/proxy.conf /etc/nginx/proxy.conf
COPY deploy/nginx/base-headers.conf /etc/nginx/base-headers.conf
COPY deploy/nginx/security-headers.conf /etc/nginx/security-headers.conf
# Docker: the defaults. Demo behind a Cloudflare Tunnel: CLIENT_IP=client-ip-cloudflare.conf.
# Cloud Run (deploy/gcp): BACKEND=127.0.0.1:9080 (the backend runs next to nginx in the same
# service) and CLIENT_IP=client-ip-lb.conf (visitor address from the load balancer's header).
ARG BACKEND=backend:8080
ARG CLIENT_IP=client-ip.conf
COPY deploy/nginx/${CLIENT_IP} /etc/nginx/client-ip.conf
RUN case "$BACKEND" in *[!A-Za-z0-9.:-]*) echo "bad BACKEND" >&2; exit 1;; esac \
 && sed -i "s/server backend:8080;/server ${BACKEND};/" /etc/nginx/nginx.conf \
 && grep -q "server ${BACKEND};" /etc/nginx/nginx.conf
COPY --from=game --chown=101:101 /build/dist /usr/share/nginx/html
USER 101
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --retries=3 CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
