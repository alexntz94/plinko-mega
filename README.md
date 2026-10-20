# Plinko

[![CI](https://github.com/alexntz94/plinko/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/alexntz94/plinko/actions/workflows/ci.yml)

A real-money Plinko game for online casinos. The player drops balls through a board of pegs and wins the multiplier of the slot each ball lands in. The casino runs the game through its player account system (PAM) with a seamless wallet: bets and wins go straight to the player's casino balance.

**What it offers**

- **The game:** 8 to 14 rows, three risk levels, up to 100 balls per drop, on desktop and phones in portrait and landscape. Seven themes (Drop Riot, Bad Bounce, Gravity Issues, Rat Race, Doomscroll, Bad Dentist, Shady Nana), each with its own scene and signature effects (Drop Riot, Bad Bounce and Gravity Issues move: 3D loops) (paint splats, bad bounces, failing gravity, comic sound effects, viral alerts, cartoon yelps).
- **Bonuses:** golden balls that multiply their prize, hot slots that light up before the drop, and a bonus meter that pays free balls. All three are switched on and tuned in the admin panel.
- **The admin panel:** live dashboard, finance and revenue reports, players, rounds and wallet transactions, odds and RTP, bonuses, themes, settings, admins and a tamper-evident audit log. Every change that affects money needs a second admin's approval.

**Built so nobody can fake a winner**

- The server decides every result with a secure random generator after the bet is taken. The browser only animates it and has no say in the outcome.
- Every round is signed, and the database refuses to change a decided round. A result edited by hand is never paid and raises an alarm.
- Odds, bonuses and settings are signed too. Edited outside the admin panel, they stop the game until an approved fix restores it.
- Hard limits on RTP, multipliers, bets and the win per round are set in the server configuration, out of reach of any admin action.

```
game/        browser client (TypeScript + PixiJS + GSAP, built with Vite); animates results, decides nothing
backend/     Java 21 / Spring Boot 4 / MySQL: game API, bonuses, PAM adapter, admin panel
deploy/      hardened nginx and MySQL images; deploy/gcp: Google Cloud deployment
tests/       end-to-end security suite and headless-browser check
```

## How a round works

1. The casino opens the game with a one-time launch token: `https://game.example.com/?token=<PAM token>`.
2. The backend calls PAM `authenticate`, creates a game session and returns limits, odds, the player's bonus meter and balance. The token can't be used again. A reload in the same browser tab resumes the session, which is kept in that tab only and gone when it closes; the balance is re-read from the PAM.
3. On **Play**, the client sends only `rows`, `risk`, `bet` and `balls`. The backend:
   - debits `bet × balls` from the PAM
   - draws the round's hot slot (when that bonus is on), then every ball's landing slot and golden boost, with `SecureRandom` and the approved odds
   - charges the player's bonus meter and, when it is full, draws its free balls
   - signs the result, bonuses included (HMAC), together with the odds version it was played on and the win limit in force
   - reads the round back from the database and checks it (`WinCheck`): the win worked out again from the stored balls, each multiplier against that odds version, the bet taken, the credit the round's own, the win within its limit
   - credits the total win to the PAM in one credit
4. The client animates the balls into those slots, shows the bonuses, and shows the balance the PAM reported.

If a debit's outcome is unknown (timeout or 5xx), it is rolled back. Failed credits and rollbacks are retried automatically with the same transaction id. A credit, live or retried, is paid only if the round passes the same check; a round that fails is blocked for review (the player sees `ROUND_UNDER_REVIEW`).

**Database load per round:** three transactions, split at the wallet calls (before the bet, between the bet and the payout, after the payout), so a crash always leaves the round in a state the recovery finishes, plus a one-row "sending" mark committed just before the credit is sent (review R4). The settings are kept in memory and re-read only when their version number changes (every approved change raises it, on every instance at once) or once a minute. The dashboard and Finance read daily totals (`round_daily_totals`) instead of adding up the whole round history; the dashboard is computed at most every 20 seconds and checks the audit chain in the background (new entries every 10 minutes, all of it daily; the audit page checks it fully on every view). Today's totals are updated incrementally: each round writes its increment (one insert-only row) in its last transaction, and a background job adds them every minute (a round paid by a retry adds itself; a counted round blocked later takes itself out). The full rebuild, from plain reads that take no locks on the rounds, stays for closed days and repairs: yesterday after midnight, today at start-up, 15 minutes later (once a rolling deploy's previous release has stopped) and every hour, and a day whose rounds changed later when it is marked in `daily_totals_dirty`. A rebuild counts only rounds whose increment is already written (`COMPLETED`, `CREDIT_PENDING`; review R2): a round still between its bet and its payout (`DEBITED`) is counted by its increment when it settles, never by the rebuild as well, and a status change and its increment are always one transaction. The settings a round uses are read once for it, as one snapshot.

**Pool size:** `DB_POOL_SIZE` (default 30) connections per backend instance, `DB_POOL_MIN_IDLE` kept open while idle. On Google Cloud the game service uses 12 (2 idle) and the admin 5: keep instances x pool size under the database's `max_connections`.

**Caching:** nginx sends the game's fingerprinted scripts with a one-year `immutable` cache, and theme files and sounds for a year when asked for with `?v=<fingerprint>` (one fingerprint per theme folder and one for the shared sounds, so a changed file gets a new address and the other themes keep theirs). Only a file that is there gets the year (answers 200, 206 and 304; review R5): a 404 (a new file asked of an instance still on the old release during a deploy, or a file removed from a theme) or a 429 from the limits is `no-store`, so neither browsers nor the CDN keep a missing picture under its `?v`. The page itself is `no-store` (launch links carry a token) and `theme.json` is always re-checked. Text files are gzipped; answers from the backend are not (they carry the session id). On Google Cloud, Cloud CDN on the load balancer keeps exactly what nginx marks as cacheable, with only `v` in the cache key.

## Run it

```sh
./scripts/generate-secrets.sh      # creates .env (settings) and secrets/ (one file per secret); never commit either
# .env: PAM_BASE_URL from your PAM; secrets/PAM_API_KEY and secrets/PAM_SECRET: the PAM's key and secret
docker compose up -d --build
```

- **Secrets** are files in `secrets/`, mounted into the containers as Docker secrets. They don't show in `docker inspect` or the process environment. `.env.example` lists the settings. If you have an older `.env` that still holds the secrets, run `scripts/secrets-from-env.sh .env` once, then remove the secret lines from `.env`.
- **Updating an existing install** (review R17.7): after `git pull`, run `scripts/generate-secrets.sh` again before `docker compose up -d --build`. On an existing install it overwrites nothing: it only adds the secret files a newer release needs that can be made safely (today the key lists `secrets/ROUND_KEYS`, `RECORD_KEYS` and `ENCRYPTION_KEYS`, one fresh key each; see [Keys](#keys)), and names any other missing secret (a database password or the PAM's key must be the value the install already uses; if they are still in `.env`, `scripts/secrets-from-env.sh .env`). Without those files Compose refuses to start.
- **Migrations** run in a short-lived `migrate` container as the schema owner, then exit. The running backend never holds the owner's password.
- **Game:** `http://localhost:8080/?token=…`. This is the public port; it serves only the game and the three game API calls.
- **Admin panel:** `http://127.0.0.1:8081/admin`, on its own port that is bound to localhost only, so reach it through a VPN or SSH tunnel. The game port never serves admin pages, and the admin port never serves the game API. On a fresh install, sign in with **admin / admin** within 24 hours. That first sign-in opens nothing but 2FA setup and "choose a new password" (12+ characters, mixed); after that the default stops working for good. If 24 hours pass first, run the recovery command (see *Forgotten password*) for a new temporary password. To start with your own password instead, put it in `secrets/ADMIN_PASSWORD` before the first start.
- **Building or testing outside Docker** needs **Java 21** (the backend doesn't compile with 17) and **Node 22** (with Node 18, Vitest and the game build fail at start-up). The Docker images bring their own.
- **Live pages:** Dashboard, Players, player details, Rounds, Transactions and Finance refresh by themselves every 5 seconds. These background refreshes don't count as activity, so an unattended screen is still signed out after 30 minutes.

**Local testing without a PAM:** in `.env` set `MOCK_PAM_ENABLED=true`, `PAM_BASE_URL=http://localhost:8080/mock-pam` and `COOKIE_SECURE=false`. **Admin → Mock PAM** then creates test wallets. **Open game** issues a fresh single-use launch token each time. Never enable the mock in production.

**Production:** put TLS in front of nginx and enable the HSTS line in `deploy/nginx/nginx.conf`. Keep `COOKIE_SECURE=true` and `REQUIRE_SECOND_APPROVER=true`. If the casino embeds the game in an iframe, change `frame-ancestors 'none'` in the game CSP to the casino's origin.

## Deploy on Google Cloud

The same images run on Cloud Run, with Cloud SQL as the database. `deploy/gcp/deploy.sh` sets everything up in named steps, so you can run and check one step at a time.

```
players ──HTTPS──> load balancer (managed certificate, TLS 1.2+, Cloud Armor)
                     └─> Cloud Run plinko-game:  nginx ──127.0.0.1──> backend
admins ───HTTPS──> IAP (Google sign-in, allow-list) ──> Cloud Run plinko-admin: backend, admin port only
both services ──private network──> Cloud SQL MySQL 8.4 (private IP only, encrypted connections only)
              └──Cloud NAT, one fixed IP──> PAM (https only)
Secret Manager ──> each service reads only its own secrets, as files
```

**You need:**

- a Google Cloud project with billing, and the Owner role on it
- a domain whose DNS you control, for the game (e.g. `plinko.example.com`)
- the PAM's address, API key and secret
- **Cloud Shell**, which already has `gcloud`, `envsubst` and `openssl`; upload or clone the repo there

IAP works best when the project belongs to an organisation (Google Workspace or Cloud Identity). Without one, set up the OAuth consent screen first (**APIs & Services → OAuth consent screen**).

**First deployment:**

```sh
cp deploy/gcp/config.env.example deploy/gcp/config.env   # fill in project, region, domain, admin emails, PAM address
deploy/gcp/deploy.sh apis       # turns on the Google Cloud APIs
deploy/gcp/deploy.sh network    # private network, private path to Cloud SQL, fixed outgoing IP, outgoing firewall
deploy/gcp/deploy.sh secrets    # random secrets in Secret Manager; asks for the PAM key and secret
deploy/gcp/deploy.sh build      # builds the images with Cloud Build, pinned by digest (only a commit CI passed)
deploy/gcp/deploy.sh database   # Cloud SQL (takes ~10 minutes)
deploy/gcp/deploy.sh db-setup   # creates the database and its two users
deploy/gcp/deploy.sh migrate    # database migrations, as the schema owner
deploy/gcp/deploy.sh deploy     # admin panel (behind IAP), then the game
deploy/gcp/deploy.sh lb         # HTTPS load balancer + Cloud Armor; prints the IP for your DNS A record
deploy/gcp/deploy.sh logs       # keeps launch tokens out of Cloud Logging
deploy/gcp/deploy.sh check      # run again once DNS points at the load balancer
```

- **DNS:** point the domain's A record at the IP printed by `lb`. The certificate becomes active up to an hour later.
- **PAM:** `network` prints the fixed outgoing IP. Give it to the PAM if it allow-lists callers. The firewall lets the services out only on port 443 (and to Cloud SQL); set `PAM_EGRESS_RANGES` in `config.env` to the PAM's addresses to allow nothing else. If the PAM uses another port, add a rule like `plinko-allow-https-out` in `deploy.sh` for it.
- **First admin:** `deploy` prints the admin panel's address. Only the admin service creates admin accounts; the public game service can't read the admin password. Sign in with a Google account from `ADMIN_EMAILS` (the list is exact: remove someone and run `deploy`, and their access is gone), then with `ADMIN_USERNAME` and the password from `gcloud secrets versions access latest --secret=plinko-admin-password`. Setting up 2FA is mandatory.

**Updates:** `build`, then `migrate`, then `deploy`. Migrations always run before the new backend starts.

**Only a commit CI passed is deployed (review R1.2):** `build` refuses uncommitted changes and any commit without a successful run of the CI workflow on GitHub (`gh run list --commit <sha> --workflow ci.yml --status success`), and writes the commit into `images.env`; `db-setup`, `migrate` and `deploy` check that commit again before they run its images. So Cloud Shell needs the GitHub CLI, signed in once (`gh auth login`). `--skip-ci-check`, anywhere on the line (also for `deploy-all.sh`), deploys anyway with a warning: only for an urgent fix while CI itself is down, and the commit must then be put through CI as soon as it runs again. `deploy` also sets Cloud CDN on the load balancer (cache and compression; the same values each time), so an install set up before that gets it with its next update. For several casinos, see below.

**Checklist after the first deployment.** The e2e suite runs on the Docker stack, not on Google Cloud, so check these by hand:

1. `deploy.sh check`: the game's own `run.app` address refuses visitors, the admin panel asks for a Google sign-in, the game loads over HTTPS with HSTS, `/admin` on the game domain is 404, plain `http://` redirects, the certificate is `ACTIVE`, and Cloud SQL has no public IP and only the users `root`, `plinko_owner` and `plinko_app`.
2. **Visitor IP:**
   - Admin panel: after signing in, **Audit log** shows `LOGIN … from <your IP>`. If it shows a Google address instead, set `ADMIN_CLIENT_IP_HOPS` in `config.env` (1 = the default; try 2) and run `deploy` again. The per-address login throttle depends on it.
   - Game: open the game, then in **Cloud Run → plinko-game → Logs** the nginx lines must start with your own IP. If they show a Google address, every player would share one rate limit: adjust `deploy/nginx/client-ip-lb.conf` before going live.
3. **Database grants:** in **Cloud Run → Jobs → plinko-db-setup → Logs**, run after `migrate`, `plinko_app` has only the table grants from the migrations (`V2__app_user_grants.sql` and later), and no `DELETE` on rounds, transactions or the audit log.
4. **A real round:** open the game through a PAM launch link, play, and find the round under **Rounds** with a valid signature.
5. If the casino embeds the game in an iframe, change `frame-ancestors 'none'` in `deploy/nginx/nginx.conf` to the casino's origin, then `build` and `deploy`.
6. **Caching and compression** (`deploy.sh check` prints these too). Take a script name from the page (`curl -s https://<domain>/ | grep -o '/assets/[^"]*\.js'`) and ask for it twice:
   ```sh
   curl -sI -H 'Accept-Encoding: gzip' https://<domain>/assets/index-<hash>.js
   ```
   Expect `content-encoding: gzip`, `cache-control: public, max-age=31536000, immutable`, and on the second request an `age:` header (seconds the CDN has held it: it came from the edge, not from Cloud Run). The same for a theme file with its version, e.g. `/themes/drop-riot/background.png?v=<v>` (the `v` from the browser's network tab). No `age` after a few requests: the CDN is off or nginx's header is wrong; run `deploy` again (it turns the CDN on) and look at **Network services → Cloud CDN → plinko-game-be** (cache hit ratio). The page itself (`/`) must never have an `age`: it is `no-store`.
7. **CDN hit ratio and metrics** (`deploy.sh check`, review 5.6 #6, 7.10a): the last hour's share of requests served by Cloud CDN, from Cloud Monitoring (the load balancer's `request_count` by `cache_result`), for the static files alone (the URL map has path rules `/assets/*` and `/themes/*` to the same backend so they are counted apart; expect 95% or more once players come in) and for every request (lower: the page and the game API are never cached), the requests that reached the game service, and whether the game's metrics arrive from the admin service's collector (`plinko_rounds_today`). The CDN can't be load-tested locally: this is how it is measured, and the "CDN hit ratio low" alert watches it.

**What changes on Google Cloud, and how security is kept:**

| On Docker | On Google Cloud |
|---|---|
| admin panel on its own port, bound to localhost | its own Cloud Run service behind IAP: only listed Google accounts reach the login, which still needs the password and 2FA. The game service never exposes the admin port |
| nginx is the only public entry | the same, behind the load balancer. The game service accepts traffic only from the load balancer (ingress setting) |
| Docker secrets in `secrets/` | Secret Manager, mounted as files (never environment variables). Each service account can read only its own secrets: the owner password only by the migration job, the root password only by the db-setup job |
| internal network with no route out | private network. Cloud SQL has no public IP and accepts only encrypted connections. Outgoing traffic is allowed only on port 443 and to Cloud SQL |
| per-IP limits in nginx | Cloud Armor (300 requests a minute per address, plus web attack rules) in front of the same nginx limits. nginx and the login throttle read the visitor's address from the one header entry Google adds, which a client can't fake |
| one backend | the game service can run several instances. A player's rounds stay one at a time through a lock in the database, and payout retries take a database lock, so two instances never retry the same payout. The admin service runs one instance, because admin sign-ins live in its memory |
| `root` only inside the database container | the database users are made with SQL by the db-setup job, because users made with `gcloud` or the console get near-admin rights on Cloud SQL |
| local volume | daily backups kept 30 days, point-in-time recovery for 7 days, deletion protection, optional standby zone (`DB_AVAILABILITY=REGIONAL`) |

Images are deployed by digest from a registry with immutable tags. The source sent to Cloud Build leaves out secrets and `.env` (`.gcloudignore`).

**Image signing (review 7.7d, on by default):** CI builds and scans the images but never pushes them; the images that run are the ones `deploy.sh build` makes in Cloud Build and pushes to the project's Artifact Registry. So they are signed there, with Binary Authorization (`BINAUTHZ=on`, the default; `off` only for a throwaway test project): every `build` makes sure the Cloud KMS signing key, the attestor `plinko-built` and the project's policy exist (each made only if missing) and attests its images. From then on every `build` attests its images by digest, every service and job is deployed with `run.googleapis.com/binary-authorization: default`, and Cloud Run refuses any image without the attestation (pushed some other way, or changed after the build), apart from Google's own images and the metrics sidecar. The person running `build` needs to sign with the key (`roles/cloudkms.signerVerifier`) and create attestations (`roles/containeranalysis.notes.attacher`); the project owner has both. A blocked deploy shows in Cloud Run's error and in Cloud Logging (Binary Authorization). Not yet tried on a real project: try it on staging first. Cloud Run's own request log is turned off for the game, because the game URL carries the launch token; nginx still logs every request, path only.

**Cost:** roughly $250–350 a month at low traffic. The main items are Cloud SQL (regional), the always-on instances of both services, the load balancer, Cloud NAT and Cloud Armor. Check with the [Google Cloud pricing calculator](https://cloud.google.com/products/calculator) for your region.

### Several casinos

Each casino gets **its own deployment** in its own Google Cloud project: its own database, secrets, network, wallet connection, admin panel and domain. Casinos never share anything, so one casino's settings, players, admins or an incident can't touch another. Each one can also run in the region its regulator asks for.

**Adding a casino:**

1. **Google Cloud:** create a project for it, e.g. `plinko-casino-x`, with billing and a budget alert.
2. **Config:** `cp deploy/gcp/config.env.example deploy/gcp/casinos/casino-x.env` and fill it in with that project, its domain (e.g. `casino-x.play.yourbrand.com`), its admins' Google accounts and its wallet address. A small casino can use `DB_AVAILABILITY=ZONAL` and `GAME_MAX_INSTANCES=3`.
3. **Deploy:** run the same steps as above with the casino's name added:
   ```sh
   deploy/gcp/deploy.sh apis casino-x
   deploy/gcp/deploy.sh network casino-x      # its own outgoing IP, for that casino's allow-list
   deploy/gcp/deploy.sh secrets casino-x      # asks for that casino's wallet key and secret
   ...                                        # build, database, db-setup, migrate, deploy, lb, logs
   deploy/gcp/deploy.sh check casino-x
   ```
4. **DNS:** an `A` record for its domain pointing at the IP printed by `lb`, set to **DNS only** (grey cloud) in Cloudflare.
5. **Admin panel:** sign in, then create its second admin and set its currency, limits, odds, bonuses and themes (each approved by a second admin).

**Updating every casino:** `deploy/gcp/deploy-all.sh` runs `build`, `migrate`, `deploy` and `check` for each casino in turn, and stops at the first failure, so a broken update never reaches the next one. `deploy/gcp/deploy-all.sh casino-x` updates only that casino; try an update on a staging project first.

**Good to know:**

- The casino files in `deploy/gcp/casinos/` hold no secrets but stay local (git ignores them). Keep a copy somewhere safe.
- **Cost:** about $150–350 a month per casino, depending on size (Cloud SQL, always-on instances, load balancer, NAT).
- **Reports:** each casino has its own Finance page; add them up for totals across casinos.
- **When to merge:** with many casinos (around 5 or more), or when you need daily reports across casinos, one shared multi-casino platform becomes worth building. That means a casino code in every launch link, wallet and table, with per-casino settings and logins.

## Live demo

A public demo with fake credits. `https://<demo address>/demo` is a lobby with one card per theme that is switched on in **Admin → Themes**. Choosing one gives the visitor a fresh wallet of 1,000 demo credits and opens the game in that look.

- **What players see:**
  - A "DEMO – no real money" label is shown the whole time.
  - Reloading the page keeps the same game.
  - At zero credits the game offers **Start a new demo**, which goes back to the lobby.
  - The lobby is plain HTML with no scripts. Looking at it costs nothing; a wallet is made only by a card's Play button, a form POST. Link previewers and crawlers only ever GET, so they never make one; an old `/demo/play?theme=…` link just opens the lobby.
  - Shared on WhatsApp, Telegram, iMessage, Slack and the like, the link shows a preview card (`game/public/demo-card.jpg`). Its address comes from `GAME_URL`, so set that to the demo's public address.
- **The game is the real one:** every ball is decided and signed by the server, as with real money.
- **Safety:**
  - The demo exists only with `DEMO_MODE=true`. The backend refuses to start in demo mode unless the wallet is the built-in mock PAM, so a real-money server can never hand out demo wallets. On a normal server `/demo` is 404.
  - Each visitor can make a new wallet at most every 10 seconds (a few in a burst); the lobby itself has the normal page limits.
  - New wallets are capped per day (`DEMO_WALLETS_PER_DAY`, default 2,000). The starting credits are `DEMO_CREDITS`.
  - Run the demo on its own server with its own secrets, never next to production. The admin panel stays private (SSH).

### Try it from your Mac (no account, 5 minutes)

In `.env`: `MOCK_PAM_ENABLED=true`, `PAM_BASE_URL=http://localhost:8080/mock-pam`, `COOKIE_SECURE=false`. Then:

```sh
DEMO_MODE=true docker compose up -d --build
docker run --rm --network plinko-frontend-main_edge cloudflare/cloudflared:2026.9.3@sha256:072c067d25ccbe61d46e18f0d0723255f2bb5304f7317caa95b27031520ff92c tunnel --no-autoupdate --url http://web:8080
```

Cloudflare prints a random `https://….trycloudflare.com` address; share it with `/demo` added (locally: `http://localhost:8080/demo`). It works while your Mac is on and the second command runs. In this quick mode all visitors share one rate limit, so it's only good for a few people.

### Always on, for free: Oracle Cloud + Cloudflare Tunnel

Oracle Cloud's *Always Free* tier includes an ARM server (4 CPUs, 24 GB) that runs the whole stack. A Cloudflare Tunnel gives it an HTTPS address without opening any port on the server. You need a domain on Cloudflare (the free plan is enough).

1. **Server:** create an Oracle Cloud account (it asks for a card to verify you; Always Free resources are not charged). Create a VM: Ubuntu 24.04, shape `VM.Standard.A1.Flex` with 4 OCPUs and 24 GB, and your SSH key. Leave the network as it is (only SSH open). If it says *out of capacity*, try another availability domain or later.
2. **Docker:** on the server, install Docker Engine with the Compose plugin ([docs.docker.com/engine/install/ubuntu](https://docs.docker.com/engine/install/ubuntu/)).
3. **The app:** clone the repo, run `./scripts/generate-secrets.sh`, and in `.env` set `MOCK_PAM_ENABLED=true`, `PAM_BASE_URL=http://localhost:8080/mock-pam`, `COOKIE_SECURE=false` and `GAME_URL=https://demo.yourdomain.com/`.
4. **Tunnel:** in Cloudflare, go to **Zero Trust → Networks → Tunnels → Create a tunnel** (type *Cloudflared*). Add a public hostname `demo.yourdomain.com` with service `HTTP`, `web:8080`. Copy the tunnel's token and save it on the server:
   `read -rs t && printf '%s' "$t" > secrets/CLOUDFLARE_TUNNEL_TOKEN && chmod 644 secrets/CLOUDFLARE_TUNNEL_TOKEN; unset t` (paste the token and press Enter; it isn't shown or kept in the shell history)
5. **Start:** `docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d --build`
6. **Share:** `https://demo.yourdomain.com/demo`
7. **Admin panel** (to choose the themes in the lobby and switch bonuses on): `ssh -L 8081:127.0.0.1:8081 ubuntu@<server IP>`, then open `http://127.0.0.1:8081/admin` and sign in with **admin / admin** within 24 hours (then 2FA and a new password).

`docker-compose.demo.yml` turns on demo mode and the mock PAM, and binds nginx to `127.0.0.1`, so only the tunnel reaches it. It reads each visitor's address from Cloudflare's `CF-Connecting-IP` header, which Cloudflare sets itself, so the per-visitor limits work. To start the demo afresh, run `docker compose -f docker-compose.yml -f docker-compose.demo.yml down -v`, which deletes all demo data.

## Game client

The game in `game/` was rewritten in October 2026 and replaced the first version (jQuery + CreateJS), which has been removed.

- **Stack:** TypeScript, PixiJS 8 (WebGL drawing), GSAP (animation), Howler (sound), built by Vite. Versions are pinned in `game/package-lock.json`.
- **Build:** inside the web image (`deploy/nginx/Dockerfile`) with a digest-pinned Node image. Only the built files go into nginx; Node and the sources don't. Before the build it makes, per theme: half-size copies of the `@2x` pictures (pinned `vips`), one texture atlas of the small pictures (`game/scripts/theme-atlas.mjs`, lossless WebP), WebP copies of the big pictures (pinned `cwebp`) and AVIF copies of those (pinned `avifenc`, kept only where smaller than the WebP).
- **Caching:** scripts and styles carry a hash in their names. Theme files and sounds are asked for with `?v=<fingerprint>`, one fingerprint per theme folder and one for the shared sounds (`game/vite.config.ts`, the build's copies included), and are cached for a year (`public, max-age=31536000, immutable`). A new or changed theme changes only its own addresses; players and the CDN keep the rest. `theme.json` and the page are always re-checked.
- **Pictures:** the game finds the formats the browser shows by decoding a tiny AVIF and a tiny WebP (Safari shows both but can't make them, so a canvas test is wrong there), then asks per picture for AVIF, else WebP, else PNG, only for copies the build made (`game/src/images.ts`); a format that fails to load falls back to the next. The `@2x` pictures only on screens with 1.5 device pixels per pixel or more, the half-size copies elsewhere. The small pictures (buttons, ball, pegs, slots, sliders) come from the theme's atlas: 2 requests instead of about 25; if the atlas fails, or the browser shows neither WebP nor AVIF, they come one by one.
- **Page weight:** `npm run size-budget -- <built copy> --built` (`game/scripts/theme-size-budget.mjs`) counts what a first visit downloads per theme, and its requests. On the image build of 2026-10-19 (heavier orientation, Chrome): 1.03 to 1.57 MB and 23 to 25 requests per theme, from 1.20 to 2.11 MB and 42 to 49 requests before the atlas and AVIF; the video (0.26 to 0.95 MB) comes after the player's first round and is reported apart ("after 1st round"). CI fails when any theme's first visit is over 2 MB, or a theme grows past its budget in `game/scripts/theme-size-budget.json` (its size then + 40 KB; `--update` rewrites it, e.g. after making a theme lighter). The script reads `VIDEO_WAITS_FOR_FIRST_ROUND` in `game/src/backdrop.ts`: if the video ever loaded before the first round again, it would be counted in the first visit.
- **Videos (review N15.3, 4.x):** every moving background is under 1 MB: the ones over 1 MB were re-encoded on 2026-10-19 (ffmpeg two-pass, same size, frame rate and length, no audio; MP4 H.264 High with faststart, WebM VP9) to 0.88-0.95 MB: Fatal Attraction 3.57 / 3.01 MB (landscape / portrait MP4) to 0.88 / 0.89, Big Brother 3.09 / 2.49 to 0.92 / 0.95 (WebM 3.06 / 2.46 to 0.90 / 0.90), Toxic Lab 2.10 / 1.61 to 0.89 / 0.88 (WebM 1.82 / 1.52 to 0.91 / 0.91), System Failure 1.91 / 1.75 to 0.89 / 0.89 (WebM 1.47 / 1.44 to 0.90 / 0.91), and Inked & Damned, Gravity Issues, Holy Sin, Doomscroll (portrait) and Rat Race from 1.02-1.10 MB to 0.88-0.93 MB. Re-encode a new video the same way when it is over 1 MB. On every device the video loads only after the player's first round (never on the first visit), is left out on a slow connection and for players who ask for less motion, and any player can stop it (or start it at once) in the settings.
- **Files:** `game/src/` is the code. `game/public/` holds the files served as they are: `themes/<id>/` (artwork and `theme.json` for each look), `assets/sounds/`, `css/fonts/` and `icon.ico`.
- **Security:**
  - The client still decides nothing. It sends rows, risk, bet and balls, then animates each ball into the slot the server picked.
  - Everything is bundled into files with no inline scripts or styles, so the strict CSP stays unchanged. PixiJS runs without `eval`.
  - Game state lives inside the module, not in global variables.
- **Phones:** sharp text at every pixel density; portrait and landscape layouts. When the phone turns, the game follows each size the browser reports.
- **Control center:** one panel drawn in each theme's colours (no extra artwork; a theme can ask for the shaded "glass" look and its own drawn DROP button in `theme.json`, `ui`). On phones: the LOW / MEDIUM / HIGH switch, then the bet (tap the amount for the quick picker) | the **DROP** button | the rows, then BALANCE and WIN. On wider screens the same pieces stand in a column left of the board. Players can switch to the **classic panel** (credit, bet with ½ / 2X, risk arrows, rows and balls sliders, PLAY) with the mixer button in the game's settings column; the game remembers the choice on that device.
  - **Tap** DROP: one ball (one round). Tap again while balls are still falling; quick taps queue up.
  - **Hold** DROP (*Hold & Drop*): after a moment the button turns into a glowing STOP with a SPINS counter and drops round after round, about two a second. It stops when the player lets go or taps STOP, when the balance can't pay the next drop, after a round that wins 10× or more, or on any error. Every drop is still one normal round, decided and signed by the server.
  - BALANCE is always the server's balance less the wins of balls still in the air, so it never shows money that hasn't been paid. Bet, risk and rows lock while balls are falling.
- **Themes:** sixteen looks (Drop Riot, Bad Bounce, Gravity Issues, Rat Race, Doomscroll, Bad Dentist, Shady Nana, The Scam Squad, Shurda Plinko, Inked & Damned, System Failure, Holy Sin, Toxic Lab, Big Brother, Fatal Attraction, Clown Business), switched on or off in **Admin → Themes**. Each sets its font, board colours, sharper `@2x` images and signature effects (`board.fx`) in `theme.json`; Drop Riot, Bad Bounce, Gravity Issues and Shady Nana also draw their own prize slots (`board.slotArt`, `item_slot_0..4`), a themed ball and pegs, and use the glass control panel (`ui.style`, `ui.drop`). Rat Race, Bad Dentist and Doomscroll draw their controls in comic ink (`ui.style: "ink"`) with their own DROP button and controls switch (`ui.drop`, `ui.controls`). A theme can also have a moving background (`video`: a short muted loop per orientation, MP4 with a WebM copy of the same name; rendered in Blender: Drop Riot's chrome crown in neon, Bad Bounce's ball bouncing badly in a boiler room, Gravity Issues' black hole swallowing coins, balls and a rubber duck; The Scam Squad's claymation internet and card scammers in their basement scam centre (the Phisher with a credit card on his hook, the Skimmer fanning stolen cards, the laser-eyed Crypto Boss on his rug, Tech Support and his virus pop-up, Spam the pigeon), animated on twos like stop-motion; and the four painted themes, where only the characters move, 8 drawings a second like an old cartoon: Rat Race's rats in suits, Doomscroll's influencer feed, Bad Dentist's runaway tooth and dentist, Shady Nana's bingo gang and cat). The Scam Squad is all clay, rendered in Blender (`design/themes/scam-squad/blender`): logo, buttons, board, prize chips and dialogs too, and its wins slam down a rubber stamp (`board.fx: "scam"`, "TOTALLY LEGIT!", "100% REAL!"), never on losses. Shurda Plinko is a brand theme for Shurdalife (shurdalife.com, by Daki Savić): his logo and his character are used exactly as he draws them (`design/themes/shurda/brand`; the character cut out of the logo by `cutout.py`, only the paper around him removed), in a flat cartoon beach gym drawn in SVG (`scene.mjs`: socks on the washing line, sock-ball dumbbells, "SOCK DAY > LEG DAY"); sock-ball balls, dumbbell pegs, weight-plate prize slots, a moving loop where the socks swing and the sun turns (`video.mjs`), and win words in starbursts (`board.fx: "shurda"`, "SHURDA!", "NICE SOCKS!"). Switch it on only with the brand owner's agreement. Inked & Damned is an old-school tattoo studio: photo-real backgrounds made with the Z-Image Turbo model (Apache 2.0, run locally with mflux; `design/themes/inked-damned/source-background*.png`) where neon signs buzz on the wall, flash sheets re-ink from their stencils, the lamp's light swings and a real-looking snake (AI snakeskin) crawls along the skirting (`motion.py`), American-traditional flash drawn in SVG for the logo (`flash.mjs`), a black flash-card board with ivory star pegs, a blood-red ink-drop ball, price-tag prize slots, black-leather controls in Rye lettering, tattoo-phrase win words (`board.fx: "inked"`, "HOLD FAST", "DAMNED LUCKY!") and dark rockabilly music. System Failure is a server room at night, corrupting: a photo-real base (Z-Image Turbo) run through real glitch processing (`design/themes/system-failure/glitch.py`: pixel sorting, torn bars, broken blocks, RGB split, scanlines; the loop tears violently at the edges while the board's area stays clean), a black-glass board in a cyan neon tube, a corrupted pixel ball, torn RGB-split buttons, the logo in Rubik Glitch, error win words (`board.fx: "glitch"`, "ERROR 404", "SYSTEM HACKED!") and glitchy synthwave. Holy Sin is a gothic cathedral at night that turns sinister: photo-real backgrounds (Z-Image Turbo) where the candles flicker, a gust snuffs them one by one, the stone angels' eyes glow red, the stained glass bleeds red and lightning flashes before the candles relight (`design/themes/holy-sin/motion.py`); a black marble altar board in a carved gold frame, a gold ball, stained-glass prize tiles, gold and marble buttons, the logo in blackletter gold leaf, blessed win words (`board.fx: "holy"`, "AMEN!", "HALLELUJAH!") and dark organ-and-choir music. Toxic Lab is a secret lab seen through a biohazard scanner: X-ray radiograph backgrounds (Z-Image Turbo) where the specimen floats, breathes and beats in its tank, a thermal scan shows it white-hot, and it lunges at the glass in a containment breach with shockwave, shake and red alarms (`design/themes/toxic-lab/motion.py`); a scanner-screen board with corner brackets, a radioactive ball, warning-tag prizes, flat HUD buttons in Share Tech Mono, biohazard win words (`board.fx: "toxic"`, "MUTATION!", "OUTBREAK!") and lab ambience with heartbeat beeps. Big Brother is a security DVR's split screen: a wall of CCTV feeds (Z-Image Turbo photos turned into low-fi camera footage by `design/themes/big-brother/dvr.py`: low resolution, fisheye, grain, scanlines, rolling bars, each camera's name, clock and REC dot) where a hooded figure turns up far down a corridor, then close in a hallway, before every camera shows his glowing night-vision eyes ("I SEE YOU") and the feeds drop to SIGNAL LOST; a board that is one more camera tile, an eyeball ball, camera-lens pegs and DROP button, threat-level prize slots, square DVR buttons in VT323, a red tracking box that follows each falling ball, and wins filed as classified reports with a red stamp (`board.fx: "cctv"`, "CLASSIFIED", "TOP SECRET"). Fatal Attraction is a 1940s film noir in black and white where the only colour is blood red: photo-real film stills (Z-Image Turbo) played like a reel of old film (`design/themes/fatal-attraction/motion.py`: grain, dust and scratches, rain on the glass, cigarette smoke, headlights sweeping through the blinds, a red neon sign outside that sputters and dies, and a red-orange muzzle flash in the dark); a black lacquer board in a thin silver frame, a pearl ball, bullet-end pegs, matchbook prize slots, a rotary-telephone DROP button, black and silver buttons in Bebas Neue, case-file dialogs stamped CASE CLOSED; the logo is a neon sign whose lit tubes lie over it in layers that buzz and sputter on the start screen (`logoNeon`: the number of `logo_neon_<n>.png` / `logo_neon_<n>_p.png` layers; steady for players who asked for less motion); wins of 2x and up are shots fired into the board, leaving bullet holes (`board.fx: "noir"`, three shots at 10x); revolver, vibraphone, piano and muted-trumpet sounds and smoky noir jazz. Clown Business is a crooked carnival machine at night, rendered in Blender (`design/themes/clown-business/blender`): an antique clown automaton in a glass fortune cabinet (a cracked porcelain face painted by Z-Image Turbo and wrapped on the 3D head, glass eyes that roll, lids that blink, a hinged ventriloquist jaw) that moves like a machine: its head snaps round, its jaw clacks a laugh, it stares and beckons you closer while the light in the case nearly dies; gears turn, marquee bulbs chase, dust drifts, a Ferris wheel turns in the fog behind. Dark peeling wood and brass pieces, a red clown-nose ball, brass rivet pegs, ticket-stub prizes, a red arcade DROP button in a ring of bulbs, a marquee-sign logo whose bulbs flicker (`logoNeon`), a jack-in-the-box clown springing out of good landings (`board.fx: "clown"`), music-box, calliope, squeeze-horn and wind-up sounds with the machine's mechanical laugh, and a creepy carnival waltz. The still background shows until the video plays, for players whose device asks for less motion, and for players who stop it with the play / pause button in the settings column (remembered on that device). A theme can also have music (`music`: a quiet seamless loop, MP3 with an OGG copy of the same name; The Scam Squad's is heist jazz, made with ACE-Step): it starts with the player's first tap and the sound button and a hidden tab silence it with the effects. Every theme also has its own sound effects (`sounds`: the names it ships as `sfx_<name>.ogg` with an `.mp3`; anything left out comes from the shared set in `/assets/sounds`), made with code by a small synthesizer (`design/sound/synth.py`, one `design/themes/<id>/sounds.py` per theme, no samples to license): the peg tick climbs in pitch row by row as a ball falls and is heard from where the peg is, landings sound different for a loss, a small win, 2x and 10x, and big wins have their own celebration sound. Each theme is a folder in `game/public/themes/<id>/` (images, `theme.json`, fonts with their licences).
- **Bonuses** are decided and signed by the server with the round (Admin → Bonuses); the game only shows them: golden balls, the hot slot lighting up before the drop, the bonus meter (markers on the slots that charge it) and free balls. The meter lives in the player's signed rounds, not in a separate table, so it can't be edited or rolled back by hand. Its pot is kept as value, so filling it on one board and being paid on another gains nothing.
- **Effects** (sparks, ball collisions, landing bursts, BIG/MEGA/EPIC WIN at 10×/25×/50× the round's cost) are visual only: every ball still lands in the slot the server chose, and the amounts shown are the server's. Players who ask their device for reduced motion get calmer versions.
- **Local development:** `cd game && npm ci && npm run build`.

## Admin panel

| Page | What it does | Who |
|---|---|---|
| Dashboard | bets, wins, GGR, actual RTP per currency; wallet issues; signature and audit-chain checks | all |
| Finance | platform revenue (GGR = bets − wins) and margin for any date range: per-day chart, by risk and rows, top winning/losing players, wallet reconciliation, daily CSV to match against the PAM statement | all |
| Players | players from the PAM, live balance, round history, block / unblock | view: all, act: operator |
| Rounds / Wallet transactions | every round and wallet call; retry a stuck credit or rollback | view: all, retry: admin |
| Odds | per rows (8–14) and risk: multiplier + probability per prize position, live RTP | propose: operator |
| Approvals | approve or reject proposed changes (odds, limits, currency, admin access); decided history with expandable rows | admin (not the proposer) |
| Settings | platform currency and bet limits (via approval), stop / start the game, Hold & Drop on/off (markets that ban autoplay: switch it off; operators can switch it off, only admins on), PAM connection test | operator / admin |
| Admins, Audit log | accounts and roles, 2FA reset, tamper-evident audit log | admin |
| Themes | the game's looks: switch each on or off, choose the default | view: all, change: operator |
| Bonuses | paying features (golden ball: a ball's slot pays ×boost; hot slot: in some rounds one slot, drawn before the balls, pays ×boost; bonus meter: balls landing on the board's 2×+ positions charge a per-player meter and part of their bet goes into a pot, paid out as free balls when full). Proposed together with all 21 odds tables rebuilt so each table keeps its total RTP; base and total RTP per table | propose: operator, approve: admin |

**Lists:** Players, Rounds, Wallet transactions, Audit log and the Approvals history have filters (kept in the address bar, so a filtered view can be bookmarked), sortable columns, page numbers with totals (25 / 50 / 100 per page) and **Export CSV** of exactly the filtered list. Exports are recorded in the audit log.

**Currency:** the platform plays in one currency (Settings, default USD; changes need a second admin). A player whose PAM wallet is in another currency can't open the game, and no money moves. Only currencies with 2 decimal places are offered or accepted (every amount is rounded, signed and sent to the PAM with 2 decimals); JPY, KRW (0) or KWD, BHD (3) would need the decimals to come from the currency throughout.

**Days:** Finance, the dashboard's day figures and the daily totals count days in `REPORTING_TIME_ZONE` (default UTC; e.g. `Europe/Malta`), so they match the casino's books. Times are stored and shown in UTC. Changing the zone rebuilds the daily totals once, in the background.

### Changing the odds

**Quick way, Set RTP:** at the top of **Odds**, type a target RTP (e.g. `95`) and choose all tables or one row count, then **Preview** and **Propose these tables**. The server rebuilds every table from its standard shape. Payouts of 1x or less stay as they are, and the winning part of each multiplier shrinks or grows until the RTP is exactly the target. A different admin approves it once under **Approvals**. Fresh installs start at 96%. The full guide for operators is the *Plinko Odds Guide* doc.

**By hand:** on **Odds**, choose the row count. Each risk level has one line per prize position (centre → edges):

- **Multiplier**: win = bet × multiplier.
- **Probability %**: chance of landing on either slot of that position. The column must add up to 100.
- **RTP** = Σ multiplier × probability. It updates live as you type.

"Physical board probabilities" loads the natural 50/50-bounce distribution, and "Standard multipliers (96%)" loads the default table. **Propose change** files a request. Nothing changes until a *different* admin approves it under **Approvals**. The change is validated again at approval time and logged.

Hard limits come from the server environment and can't be exceeded from the panel: `LIMIT_MIN_RTP`/`LIMIT_MAX_RTP` (85–99.7%), `LIMIT_MAX_MULTIPLIER` (1000x), `LIMIT_MAX_BET`, `LIMIT_MAX_BALLS`, `LIMIT_MAX_WIN_PER_ROUND`.

### Bonuses

Three paying features, each switched on and tuned in **Admin → Bonuses**. They start switched off. The server draws every bonus with `SecureRandom` together with the round, signs it with the result and freezes it in the database. The game only shows it.

| Bonus | What the player sees | Settings (limits) |
|---|---|---|
| **Golden ball** | a ball turns golden; its prize is multiplied by its boost | chance per ball (0–10%); 1 to 6 boosts (above 1x, at most 100x), each with a weight, weights add up to 100%. Suggested start: 1%, 2x/3x/5x/10x at 60/25/12/3 |
| **Hot slot** | in some rounds one slot lights up before the drop; every ball landing in it pays its prize × the boost | chance per round (0–100%); boost (above 1x, at most 10x). Suggested start: 20%, 3x |
| **Bonus meter** | paid balls landing on a big prize charge the player's meter and put part of their bet into a pot; a full meter pays the pot out as free balls on the same board | charges to fill it (3–100); prize that charges it (above 1x, at most 1000x; the game marks those slots); share of the bet put into the pot (above 0%, at most 100%); free balls paid (1–50). Suggested start: 10 charges, 2x, 30%, 10 balls |

- **Bonuses come out of the RTP, not on top of it.** A bonus change is proposed together with all 21 odds tables rebuilt, so each table keeps its total RTP (base game + bonuses). The page shows the base and total RTP for every table, and the hard limits (`LIMIT_MIN_RTP`/`LIMIT_MAX_RTP`) apply to the total.
- **At most +15%:** bonuses may add at most 15% on top of the base game's return, so the base game keeps its value. The smallest board (8 rows) is checked, because a hot slot adds the most there. Settings that go over are refused with the exact figure (e.g. "these settings add 19.33% on 8 rows").
- **Four-eyes:** an operator proposes a change and a different admin approves it under **Approvals** (change type `BONUS`). The settings are signed like the odds: edited in the database, they stop the game until an approved fix restores them.
- **The meter is safe to carry:**
  - It lives in the player's signed rounds, not in a separate table, so it can't be edited or rolled back by hand, and it carries over between sessions.
  - The rounds form a chain: each meter round records the state it continued from, and the database lets only one round continue from any state. Two rounds played at the same time can never both pay out the same pot; the second is refused and its bet refunded.
  - The pot is kept as value, not as a count of balls, so filling it on a cheap board and being paid on an expensive one gains nothing.
  - Free balls don't charge the meter. A round pays a full meter at most 3 times; further charges wait for the next round.
  - The most a round can pay, bonuses and free balls included, is still capped by `LIMIT_MAX_WIN_PER_ROUND`.

### Forgotten password and account recovery

1. **Forgot password?** on the login page: the admin enters their username. Every other admin then sees the request (badge on **Admins**). The page answers the same whether the account exists or not, and is rate-limited.
2. Another admin confirms it's really them (call or meet), then clicks **Reset password** on **Admins**.
   - A random 20-character temporary password is shown once, to give in person or over a trusted channel.
   - It works for 24 hours and opens only the "choose a new password" page.
   - **The account's 2FA is kept**, so the admin who did the reset still can't sign in as them.
3. Lost phone: **Reset 2FA**. For an OPERATOR or ADMIN account this needs approval by another admin, who is neither the proposer nor the target and isn't linked to the proposer (see below). The owner then signs in with their password and scans a new QR code.
4. Nobody can sign in, or there's no independent approver (for example, a single admin who lost their phone). Someone with server access runs:

   ```sh
   docker compose run --rm --no-deps backend --recover-admin=USERNAME --server.port=0
   ```

   This enables and unlocks the account, prints a temporary password, clears its 2FA, and records `ADMIN_RECOVERED` in the audit log.

No reset links are ever sent by email or chat.

**No puppet accounts.** Two-person approval switches on for good once a second ADMIN has set up 2FA. From then on these need another admin's approval:
- creating an OPERATOR or ADMIN account
- granting the OPERATOR or ADMIN role
- disabling or demoting an ADMIN (the admin being removed may approve it themselves)
- resetting an OPERATOR's or ADMIN's 2FA

Other rules:
- **No single-admin fallback.** Disabling or demoting admins never switches approval off again. If nobody can approve, the action is refused and only the server recovery command remains.
- **Linked accounts can't approve each other.** Whoever creates an account, or resets its 2FA, could know its credentials. That admin and that account never approve each other's changes, even through a chain of accounts, so a third admin has to.
- **No password reset for an account without 2FA.** It's refused for any OPERATOR or ADMIN that hasn't set up 2FA, because the temporary password would hand over the account.
- **Approvals are checked at the moment of approving.** The approver's account is checked again, and a proposal expires after 7 days. A proposal is refused if what it changes was changed in the meantime.
- **Account changes sign out at once.** Disabling an account, changing its role, or resetting its password or 2FA ends its open sessions straight away.
- **First password.** Every new account replaces the password its creator chose at first login.

Set up the first two admins together, in person: until the second admin has set up 2FA, the first one acts alone.

## PAM API contract

The PAM implements five JSON endpoints under `PAM_BASE_URL`. Every request carries `X-Api-Key` and `X-Signature: hex(HMAC-SHA256(PAM_SECRET, raw body))`. Every response must carry `X-Signature` computed the same way over the response body. Unsigned responses are rejected.

| Endpoint | Request | Success response |
|---|---|---|
| `POST /authenticate` | `{token}` | `{playerId, username, currency, balance}` |
| `POST /balance` | `{playerId, token}` | `{balance, currency}` |
| `POST /debit` | `{playerId, token, transactionId, roundId, amount, currency, gameId}` | `{transactionId, balance, reference}` |
| `POST /credit` | same as debit | `{transactionId, balance, reference}` |
| `POST /rollback` | `{playerId, token, transactionId, originalTransactionId, roundId}` | `{transactionId, balance, reference}` |
| `POST /transactions` (optional) | `{gameId, from, to, after, limit}` (ISO times, `after`: the last id of the previous page) | `{transactions: [{transactionId, type, roundId, playerId, amount, time}], next}` |

- **Errors:** return 4xx with `{code, message}`. Use `INVALID_TOKEN` and `INSUFFICIENT_FUNDS` where they apply, `LIMIT_REACHED` when one of the player's responsible gambling limits (loss, deposit, time) stops the bet, and `PLAYER_EXCLUDED` (or `PLAYER_BLOCKED`) for a self-excluded or blocked player; the game shows each to the player as such. A timeout or 5xx is treated as an *unknown outcome*, so debits are rolled back and credits retried.
- **Refusals (review R10):** only these codes, with a 4xx other than 409, tell the game that the wallet did **not** apply the transaction: `INSUFFICIENT_FUNDS`, `INVALID_TOKEN`, `LIMIT_REACHED`, `PLAYER_EXCLUDED`, `PLAYER_BLOCKED`. Every other 4xx, and every 409 whatever its code, is read as "the wallet may hold this transaction id": a debit is then rolled back (harmless if it never arrived, R1.3), a credit or refund is not taken as refused, so a blocked round can't be paid or refunded by a settlement until the casino confirms it. Use exactly these codes for refusals, and 409 only for the answers in R1.2, R1.4 and R1.5.
- **Idempotency:** a round's credit and rollback always use the same `transactionId`, derived from the round, so the PAM pays a round at most once. The PAM must follow these rules:

  | # | Rule for the PAM | Why |
  |---|---|---|
  | R1.1 | A repeated `transactionId` returns **HTTP 200 with the original answer**, never 409 or "duplicate". It may add `"repeated": true` (the game logs it). | Any other answer leaves the game unable to tell whether the money moved: a paid round would stay "credit pending" until a person checks it. |
  | R1.2 | The same `transactionId` with different contents (player, round, amount, currency, type, or the debit a rollback cancels) returns 409 `TRANSACTION_MISMATCH`, and no money moves. | A reused id must never push a different payment through. |
  | R1.3 | A rollback for a debit the PAM never saw returns 200, refunds nothing, and the id is remembered as cancelled. | When a debit times out, the game can't tell whether it arrived. |
  | R1.4 | A debit arriving after its own rollback returns 409 `TRANSACTION_ROLLED_BACK`. | A late bet must not be taken after the game cancelled it. |
  | R1.5 | A repeated rollback never refunds twice; a rollback for a round that was already paid returns 409 `ROUND_SETTLED`. | A paid round can't also have its bet refunded. The game then stops retrying and raises an alarm for review. |
  | R1.6 | Transaction ids are remembered at least as long as a round can be retried. | A late retry must still be recognised as a repeat. |
  | R1.7 | A launch token is valid at most 24 hours after it was issued, and only once. | The game remembers used tokens for 31 days; after that, only the token's own expiry stops a leaked launch link from being replayed. |
- **Echo the transaction:** `debit`, `credit` and `rollback` responses must include the request's `transactionId`. A signed answer for a different transaction is rejected, so an old "OK" can't be replayed.
- **Refunds:** only refund an `originalTransactionId` that is a debit of the same player and round (see R1.3–R1.5).
- **HTTPS only:** `PAM_BASE_URL` must be `https://`. Set `PAM_ALLOW_HTTP=true` only for a PAM on the same private network.
- `credit` and `rollback` must be accepted by `playerId` even after the player's token has expired.
- Amounts are decimals with 2 places, in the player's currency.
- **Transaction list** (optional, review 7.1): the wallet's own record of this game's money movements, by transaction id, a page at a time. **Finance -> Check against the wallet** compares it with the game's, after a backup restore or when the two sides' figures differ. Without it, ask the casino for an export.

`backend/src/main/java/com/plinko/controller/mockpam/MockPamController.java` is a working reference implementation of all of it, R1.1–R1.7 included.

**Certifying a wallet:** the casino's wallet team runs `tests/pam_certify.py` (Python 3, nothing to install) against their test wallet before the game is connected to it: `PAM_API_KEY=... PAM_SECRET=... python3 tests/pam_certify.py --url https://<their wallet>/plinko --token <a fresh launch token of a test player with 1.00 or more>`. It checks the signing both ways (requests with a wrong signature or key refused, every answer signed), each endpoint's answer, R1.1–R1.5 and R1.7, the error codes the game shows, and the optional transaction list, and prints PASS or FAIL per check (exit 1 on a failure). Every amount is 0.01 under its own transaction ids (`certify-...`), and the balance ends where it started. Optional: `--expired-token` (a token issued over 24 hours ago, R1.7), `--token2` (a second token of the player), and `--save run.json` now with `--repeat-from run.json` a month later (R1.6). The mock wallet passes it (`--url http://127.0.0.1:8081/mock-pam` on the test stack); like a real wallet must (R1.7), it accepts a launch token only once, and the game itself also refuses a used token for 31 days.

**When the wallet keeps failing:** the game retries a pending credit or refund in the background up to `retry.max-attempts` times. A round that reaches the limit is logged at `ERROR`, written to the audit log (`ROUND_RETRIES_STOPPED`) and shown in a red alarm on every admin page until someone presses **Retry** on it and it settles.

## Operations

### Tests and CI

GitHub Actions (`.github/workflows/ci.yml`) is set to run on every push, and every night (new vulnerabilities are published daily), but only while the GitHub account can run Actions: when its payments fail or its spending limit is reached, GitHub doesn't start the jobs at all ("The job was not started because recent account payments have failed..."), with no failed check to see. That happened from 2026-10-19 (review R1). The badge at the top shows the state of `main`, and `deploy.sh` refuses a commit without a successful run (see [Deploy on Google Cloud](#deploy-on-google-cloud)), so untested code can't go live unnoticed. A newer push cancels an older run of the same branch, never the nightly run. The actions are pinned by commit and the scanning tools' images by digest (the version in a comment beside each; Dependabot updates both the actions and their comments).

- **Backend:** `mvn verify`: the unit tests (odds, bonuses, the credit check, 5,000 real rounds, Monte Carlo RTP and chi-square on every board, the wallet contract) the admin side (a role matrix generated from the controllers, so a new admin endpoint without its role fails the build; the approval rules; the audit chain), the database itself on a real MySQL 8.4 (`DatabaseTest`, Testcontainers: every migration, the application user's grants, every integrity trigger, the daily totals against the rounds, the round lock, and the hot queries' index use), and a coverage bar on the money code (`bo/game`, `bo/odds`, `bo/bonus`, `pam`); the report is kept with the run. Locally the database tests need Docker: run Maven with the Docker socket mounted, or they are skipped.
- **Game client:** type check, `npm test` (Vitest: the slot layout is the server's, bets, what the player is told, the requests it sends), build, the size budget per theme (the image's game stage is built and `game/scripts/theme-size-budget.mjs` counts its files: no theme's first visit may be over 2 MB (the video loads after the first round and is reported apart), and an existing theme may not grow past its budget; see [Game client](#game-client)), and `npm audit` of what ships to players.
- **Checks:** no secrets anywhere in the git history (gitleaks; accepted test literals in `.gitleaksignore`), known vulnerabilities in the Maven and npm dependencies and problems in the Dockerfiles, compose files and Cloud Run configuration (Trivy; accepted findings, each limited to its files, in `.trivyignore.yaml`), and migrations that are safe for a rolling release (`scripts/check-migrations.sh`).
- **Images:** every image built and scanned (fixable HIGH or CRITICAL fails the run), with a CycloneDX SBOM per image kept for 90 days. A `v*` tag also attaches the SBOMs to the tag's GitHub release (the `release` job), where they stay.
- **End to end:** on pull requests, on `main` and nightly, the full attack and end-to-end suite on a throwaway stack (`scripts/test-env.sh` makes its secrets): pytest (`tests/e2e`, pinned in `tests/requirements.txt`), one module per area, run in file order; the JUnit report is kept with the run for 14 days.
- **Browser:** then, on a fresh stack, `tests/browser_check.mjs` and `tests/game_ui.mjs` (Playwright, pinned image, Chromium and WebKit, the engine of Safari and every iPhone browser): every theme in both orientations under the real Content-Security-Policy, a full round of 100 balls played to the end (completed and paid on the server), the phone turned mid-round; then each theme's first screen in both browsers at 1, 2 and 3 device pixels per pixel: the big pictures as AVIF or WebP (never PNG), the small ones from the atlas, `@2x` pictures only from 1.5. At 1x the first screen, landscape and portrait, with "less motion" on so it stands still, is compared with its baseline in `tests/baselines/` (a quarter of the size; at most 2% of the pixels may differ, 5% in WebKit, which draws glows a little differently; `node tests/game_ui.mjs --update-baselines` writes new ones from Chromium, look at them before committing). Any console error or CSP violation fails it, and so does running it without launch tokens (`tests/launch_tokens.py` makes one mock wallet per theme). The screenshots, and the differences of a failed comparison, are kept with the run for 7 days.
- **Nightly:** the same jobs as a push to `main`, plus a stage of its own (also by hand: *Run workflow* with *nightly* ticked): the backend tests with the Monte Carlo at 2,000,000 rounds and the query-plan test at 2,000,000 rows (`MONTE_CARLO_ROUNDS`, `QUERY_PLAN_ROWS`, given as system properties and environment variables), then on a fresh stack nginx's limits, Lighthouse, the load test below (200 players, 3 minutes) and the load scenarios (the ramp to the breaking point, a 2-minute wallet outage, a database restart, and 2,000,000 seeded rounds with 500,000 of them today). The reports are kept with the run for 30 days (the wallet files, which hold launch tokens, are left out). Pushes never run this stage.
- **Lighthouse** (`scripts/lighthouse.sh <test.env> [theme ...]`, nightly): Lighthouse CI (pinned) with a phone profile on the game as players get it, through nginx, for drop-riot, rat-race (the heaviest pictures) and fatal-attraction (the heaviest video), each with a fresh launch token. The budget is `tests/lighthouse/lighthouserc.json`: the page weight (6 MB, videos included), scripts, fonts, no third-party requests and no layout shift fail the run; the load times (first paint, largest paint, time to interactive, blocking time) are warnings until the targets are agreed (D7). On a laptop (2026-10-19) the three themes weighed 2.7, 3.5 and 4.9 MB, and became interactive after 13 to 14 s on Lighthouse's slow-4G phone (the WebGL board is drawn in software there).
- **iOS Safari** (`tests/ios_safari_check.sh [theme] [output folder]`, by hand on a Mac with Xcode; review N6): starts the test stack as Compose project `plinkoios` on ports 18080 / 18081 (next to `plinkotest`), makes a mock-wallet launch token, opens the game in Mobile Safari on a new iPhone simulator (made for the run and deleted after it, so nothing comes from Safari's cache) and takes a screenshot; it then reads nginx's access log for the iPhone's requests and fails if Safari asked for a PNG of a picture that has a WebP or AVIF copy, for no WebP / AVIF at all, or for a background video before the first round. The stack is taken down (`down -v`) at the end. `BACKEND_IMAGE=plinko-backend:local` reuses a built backend instead of building one; `DEVICE`, `WAIT` and `KEEP=1` are described in the script. On 2026-10-19 (iPhone 17 Pro, iOS 26.3, Rat Race): AVIF for the background, logo, exit, Plinko and result pictures, the WebP atlas for the small ones, no PNG and no video.
- **Previous release:** `scripts/compat-check.sh` starts the new release, swaps in the previous release's backend (the newest `v*` tag) on the new migrations, plays and pays rounds with it, and swaps back. The new backend must also accept every request the previous release's game client sends: `tests/client_requests.json` records them (a Vitest test fails when the client's requests change and the file doesn't), and the check replays the previous tag's copy. A release from before that file has a copy recorded afterwards from its own `game/src/api.ts`, `tests/compat/<tag>-client_requests.json` (v1.0.0's: the same six requests as today); with neither, the check fails. A request marked `goesOnWithout` (the reality check and session clock reports) is one the game doesn't wait for: a backend without its endpoint (404) is accepted, any other refusal is not. With no `v*` tag before the commit the job **fails**; tag releases as below.
- **Restore drill:** `tests/restore_drill.py` (by hand, twice a year; see [Restoring a backup](#restoring-a-backup)). On 1 January and 1 July `.github/workflows/restore-drill.yml` opens an issue "Restore drill <year>-H1/H2" with the checklist (the test stack, then a Cloud SQL restore into a staging project); assign it, do the drill that month, and close it with the result.

Dependabot (`.github/dependabot.yml`) proposes updates every week for Maven, npm, the Docker base images, the compose images and the CI actions; each one goes through the same pipeline before it is merged.

To run the end-to-end suite yourself (it uses ports 8080 and 8081):

```sh
python3 -m venv .venv && .venv/bin/pip install -r tests/requirements.txt     # once: pytest
scripts/test-env.sh /tmp/plinko-test
docker compose -p plinkotest --env-file /tmp/plinko-test/test.env up -d --build
.venv/bin/python -m pytest tests/e2e --env-file /tmp/plinko-test/test.env -q -rA       # everything, about 35 minutes
.venv/bin/python -m pytest tests/e2e --env-file /tmp/plinko-test/test.env -rA -k settlements   # one area
```

`--env-file` can be left out when `TEST_ENV` holds the path; `python3 tests/security_e2e.py <test.env> [pytest options]` still works and runs the same. Each check keeps its name: a failed one is reported by name and the test goes on, so one failure doesn't hide the others; the run ends with "N/M checks passed". The areas, in run order (`-k` takes the name after the number):

| File (`tests/e2e/`) | Area |
|---|---|
| `test_01_admin_login`, `test_02_surface` | two-step sign-in and 2FA, admin pages; CSRF and headers, the public port, the deployment |
| `test_03_wallet_rounds` | test wallets and sessions, normal rounds, Finance GGR and CSV against the database |
| `test_04_input_attacks` | bad and forged requests, parallel rounds, the round lock, rate limit, forged PAM calls |
| `test_05_wallet_faults` | the wallet failing (review 5.1 #1-#9, #11-#16) |
| `test_06_integrity`, `test_07_sessions_jobs`, `test_08_caching` | edited rounds and odds versions (5.2); session limits (5.3), daily totals, used launch tokens, retry backoff; caching and gzip |
| `test_09_odds_approvals`, `test_10_settlements`, `test_11_finance_wallet_check` | odds four-eyes and Set RTP; settling blocked rounds (7.3, N2); the wallet check (7.1) |
| `test_12_responsible_gambling` | the market's options (7.4) |
| `test_13_db_tampering` … `test_21_recovery_lockout` | the database edited by hand, admin accounts and approvals, settings, bonuses, history (2.3, 7.5), lists, forgot password, puppet admins, recovery and lockout |
| `test_22_demo`, `test_23_backend_killed`, `test_24_key_rotation`, `test_25_fresh_install` | last, as they restart the backend: the public demo, the backend killed mid-round (5.1 #10), key rotation (7.2, with a round waiting for its credit across it), and the fresh install (deletes every admin account) |

An area asks for what it needs (the admin, a second admin, the test wallets, a blocked round, ...) and makes it when no earlier area did, so `-k <area>` runs alone, on a **fresh stack** (`docker compose ... down -v`, then `up -d`): the suite enrols the admin's 2FA itself and refuses a stack where that was done before. Several areas, e.g. `-k "settlements or history"`, run together the same way.

**Load test** (review 5.6; `tests/load/`, k6 in Docker, nothing to install): on a running test stack (as above, best a fresh one), `scripts/loadtest.sh /tmp/plinko-test/test.env 200 3m` (players, then how long to hold them after a 30 s ramp). It makes one mock wallet per virtual player (`tests/load/prepare.py`), and then:

- **players** open a session with their launch token, then play round after round (a random board, bet and 1 to 100 balls) with 1 to 4 seconds between rounds, as the client sends them;
- **slow-wallet players** (`SLOW_PLAYERS`, default 5) have the mock wallet misbehave before each round (`/mock-pam/_fault`: a debit or credit answered late, a debit timing out or failing, a credit timing out); each round must give the expected answer (played, or `WALLET_UNAVAILABLE` with the bet rolled back), and the other players must not notice;
- **admins** (`ADMINS`, default 2) keep the dashboard, its live refresh, Finance and the rounds list loading, while the background jobs (daily totals, wallet check, round scan) run under the load (review N4, N5, N10). The admin is enrolled in 2FA by the script, so on a stack where something else enrolled it (e.g. the end-to-end suite, `tests/e2e`) this part is skipped.

It fails when more than 0.5% of the players' requests fail, when `play`'s 99th percentile is over `PLAY_P99_MS` (1500 ms by default; the real target is decision D7), or when a slow-wallet round or an admin page answers wrongly. The results are in `loadtest-out/summary.json` (rounds per second, `play` and session latency, each target PASS or FAIL), and the script prints how the run's rounds ended (`CREDIT_PENDING` ones are paid by the background retry within minutes). k6 talks to the backend directly on the stack's network: through nginx, all virtual players come from one address and its per-address limits (10 requests a second, 50 connections) would refuse them; set `GAME_URL=http://web:8080` to include nginx with a few players. On a laptop (2026-10-19), 200 players gave about 69 rounds a second with `play` at 22 ms median and 411 ms 99th percentile.

**Load scenarios** (review 5.6 #1, #3, #4, #5, N4; `python3 tests/load/scenarios.py <test.env> <scenario> ...`, same stack and settings: `PROJECT`, `OUT`, and `ADMIN_HOST_URL` when the admin port isn't `127.0.0.1:8081`). Each writes `<OUT>/<scenario>.json` and fails the run when its check fails:

- **`ramp`**: rounds started per second, step by step (`RAMP_RATES`, default 5 to 300, each held `RAMP_STEP`, default 45 s; k6 `ramping-arrival-rate`, up to `RAMP_MAX_VUS` players), until errors or `play`'s 99th percentile break. Per step: rounds done per second, `play` percentiles, errors, arrivals k6 had to drop, the database (`SHOW GLOBAL STATUS` deltas: commits, inserts, updates, rows read, row lock waits and time, threads running; the container's CPU) and the backend's pool (`hikaricp_*` from `/actuator/prometheus`: active, waiting, acquire time). A step is held when at least 95% of its rate was done with `play` p99 under `PLAY_P99_MS` and under 1% errors; the report gives the highest step held and where it broke.
- **`outage`**: players keep playing while the mock wallet refuses every credit of theirs (`/mock-pam/_fault` per wallet, `OUTAGE_SECONDS`, default 120; 600 is the review's 10 minutes), then answers again. Every round of the run must be `COMPLETED` or `CANCELLED` within `SETTLE_MINUTES` of the recovery, and the mock wallet's own records must show no round credited, debited or refunded twice, no round both paid and refunded, every credit equal to its round's win, and every wallet's balance equal to its start plus the game's finished rounds.
- **`db-restart`**: the MySQL container restarted under load (`docker restart`). Failed answers only while it is down (plus `RESTART_GRACE`, 30 s), except `409 ROUND_IN_PROGRESS` for a player whose round the restart cut off, until its round lock runs out (at least 300 s, reported separately); then every round settled and the same money checks.
- **`history`**: `HISTORY_ROUNDS` finished rounds (default 200,000; nightly 2,000,000) over `HISTORY_DAYS` days with their debits, credits and daily totals, inserted with SQL in batches the way the game writes them (the triggers accept them; their signatures are placeholders the hourly scan never reaches), then a run with admins on the dashboard and Finance; then `TODAY_ROUNDS` more played today (default a quarter; nightly 500,000), so the minute's daily-totals rebuild reads a big day (N4), and the same run again. It reports `play`'s percentiles with and without the big rebuild and fails when the 99th percentile more than doubles (`REBUILD_P99_RATIO`) and grows by over 100 ms.

**nginx limits** (review 5.6 #8; `python3 tests/load/nginx_limits.py http://127.0.0.1:8080`, nightly): a request body over 16 KB gets 413 and one just under doesn't; slow bodies and unfinished headers are dropped after 10 s; more than 50 requests in progress from one address get 429 (held with slow bodies sent under the request rate, so only the connection limit refuses them); a burst of play requests gets about 11 through and the rest 429. The limits are per address: players behind one mobile carrier address share 10 game requests a second, so about 30 to 50 of them at once on one address start getting 429 (idle keep-alive connections don't count against the 50).

On a laptop (2026-10-19, shared with other test containers, so only indicative): the ramp held **40 rounds a second** (`play` p99 1.0 s, 198 commits a second) and broke at 60, where the backend's pool of 30 connections was exhausted (up to 270 requests waiting for a connection, acquire time up to 0.7 s) while MySQL had CPU to spare; past that, rounds still completed (up to about 160 a second) but with `play` p99 of 4 to 7 s. The mock wallet runs inside the backend and takes a pool connection for each debit and credit, so a real wallet leaves the pool more room: size the pool and the database on staging (3.9c). The 2-minute wallet outage with 30 players left 1,709 credits waiting; all were paid 561 s after the wallet came back, about 200 a minute, which was then the retry job's batch (200 per pass, 8 at a time). Since review 3.8 a pass keeps taking batches of 200 (8 at a time) for up to 45 s while more are due, so that backlog no longer waits a minute per 200 rounds; this run hasn't been repeated since. Nothing was paid twice. The database restart (14 s down) failed 26 answers, all while it was down, and every round was settled 111 s later. On 200,000 seeded rounds without admins, the big day did not hurt play: with 21,512 rounds today `play` p99 was 1,092 ms, with 74,271 (the rebuild's read taking 4.4 s each minute) 565 ms; no row lock waits. With the two admins on the dashboard and Finance on a larger database (546,000 rounds, 126,000 and then 179,000 of them today) it failed: Finance pages took up to 60 s and timed out, and `play` p99 went from 363 ms to 3.2 s. So the rebuild (N4) held. Finance then still read today's rounds itself; since review 3.3 it reads only the daily totals, today included (kept up to date by each round's increment, see [How a round works](#how-a-round-works)), and this run hasn't been repeated since (review R14).

This only shows the code holds up and catches regressions. **The real capacity test, which sizes the database (review 3.9c) and checks the D7 targets, must run on a staging copy of production** (the same Cloud Run and Cloud SQL sizes, the casino's test wallet or the mock), from several machines or with the load balancer's per-address limits raised for the test, never against production.

### Releases

During a deploy the old and the new version run side by side for a while (Cloud Run rolls traffic over), on the same database, and players keep old copies of the game in their browsers. So:

- **Migrations expand, then contract.** A new column or table arrives in one release and is used from the next; something is removed only a release after no running version uses it. `scripts/check-migrations.sh` (in CI) refuses drops, renames, type changes and `NOT NULL` columns without a default in new migrations, unless the line says why it is safe (`-- contract: ...`).
- **The API only grows.** The backend refuses unknown request fields (a client can't slip in `slots` or `totalWin`), so a new field the client sends breaks an older backend: **release the backend first**, then the game. A field the new backend needs must be optional for one release. On Cloud Run the game and the backend ship in the same revision, so the risk is a **rollback**: the `compat` job replays this release's client requests on the previous backend and **fails** when they are refused, unless the release declares it in `tests/client-needs-new-backend` (a line `v1.2.0: <why>` naming the previous release): such a release moves traffic at once and says in its notes that rolling back needs players to reload the game.
- **Order of a release:** push, wait for CI to pass on the commit, then `deploy.sh build`, `migrate` (backward compatible by the rule above), `deploy`, `check`.
- **Tag every release** that goes live, on the commit that was deployed, with the next version: `git tag v1.4.0 <commit> && git push origin v1.4.0` (major: a contract step or anything that can't run beside the previous release; minor: new features; patch: fixes). CI checks every later commit against the newest tag (`compat` job, which fails while there is none), and a tag push attaches the images' SBOMs to a GitHub release. Never move or delete a tag once pushed.
- **Data backfills run as a separate, batched job, not inside a migration.** A migration may add columns, tables and indexes; filling or rewriting rows of a large table (`game_round`, `wallet_transaction`, `player`, `game_session`, `used_launch_token`, `audit_log`, the daily totals) is done by a job in batches by id range (e.g. 1,000 rows per transaction, under `READ COMMITTED`), which the running versions survive. `scripts/check-migrations.sh` refuses `INSERT ... SELECT` over those tables and `UPDATE` / `DELETE` of them without `LIMIT` in new migrations, unless the line says why it is safe (`-- small: ...`). V15 (daily totals) and V16 (meter pointer) backfill in batches by id range under `READ COMMITTED` (`DailyTotalsBackfill`, `MeterPointerBackfill` in `backend/src/main/java/com/plinko/migration`, 5,000 rounds / 1,000 players per committed batch). They are recorded exactly as the SQL files the previous release ships (type SQL, `V15__daily_totals.sql` / `V16__meter_pointer_and_indexes.sql`, those files' checksums; `SqlRecordedVersions`), so v1.0.0's migration step accepts the database (review R3); an install that recorded them as Java migrations (6d80fc8 to f01819f) has those rows put back as the SQL files before validation (`SqlVersionsRewritten`). `scripts/check-migrations.sh` checks Java migrations as well: a drop or rename needs `// contract:`, and one that writes a large table needs `// batched: <how>` and must not run in one transaction.
- **Rolling back** (review R3). The database stays as migrated: that is why migrations must work with the previous version too, and why every version the previous release knows stays recorded as it expects (CI's `compat` job runs the previous release's migration step on the new database and fails if it refuses it). Never roll a migration back by hand.
  - **Google Cloud:** send traffic back to the previous Cloud Run revision (Cloud Run, the service, Revisions, Manage traffic). The services run with `SPRING_FLYWAY_ENABLED=false`, so nothing else is needed. To redeploy the previous release instead, check out its tag and run `build`, `migrate`, `deploy` as usual: its migration step validates and applies nothing.
  - **Docker Compose:** check out the previous release's tag (`git checkout v1.0.0`), then `docker compose up -d --build`. Its `migrate` container runs first, validates the database and applies nothing; the backend starts after it.
  - **A migration that failed half-way** (the release's `migrate` step failed, e.g. a batched backfill cut off): a non-transactional migration can't roll back by itself, and Flyway refuses to go on ("contains a failed migration"). Fix the cause (the step's log names it), then remove the failed record and run the migrations again: Compose `docker compose run --rm migrate repair`, then `docker compose up -d`; Google Cloud `deploy/gcp/deploy.sh migrate-repair`, then `migrate`. Repair touches nothing but that record, and the backfills start again from the beginning. The previous release can keep serving players meanwhile (Cloud Run: its revision keeps the traffic).

### Background jobs

Each runs on one instance at a time (a database lock), in small batches:

| Job | When | What |
|---|---|---|
| Wallet retries | every minute | the 200 most overdue credits and refunds, 8 at a time; a refused one waits 30 s, 1, 2, 4 … up to 15 minutes; after `retry.max-attempts` an alarm |
| Interrupted rounds | every minute | rounds left half-way by a crash, once their lock has run out: the signed win paid, or the bet refunded |
| Daily totals | every minute | today and yesterday rebuilt (and a day whose round a retry settles late); the dashboard and Finance read them |
| Wallet reconciliation | every 2 minutes | each finished round checked once against its confirmed debit and credit; Finance shows the count per day |
| Round scan | hourly, nightly | the rounds played since the last pass (hourly), every round (03:30 UTC and at start-up): signature, win worked out again, limit |
| Audit chain | every 10 minutes, daily | the new entries, then the whole chain; the dashboard shows the result, the audit page checks it fully |
| Housekeeping | 04:15 UTC | used launch tokens and expired sessions older than 31 days deleted; the database refuses to delete anything younger than 30 days. Rounds and transactions are kept until compliance sets the retention (decision D6) |

Admin lists count matches up to 10,000 ("10,000+") and page no deeper than that; IDs are searched by how they start.

### Partitioning and archiving (draft, pending decision D6)

**Not built yet: a plan to agree (review 3.7b).** It waits for decision D6 (how long rounds and transactions must be kept, online and in total, and where). `game_round` and `wallet_transaction` only grow: at a million rounds a day that is about 365 million rounds and 730 million transactions a year, which slows the scans that read whole days (daily totals, Finance, the round scan), the backups and above all a restore (README, Restoring a backup). The plan: monthly partitions, and months older than the online retention moved to cold storage with everything needed to prove them later.

1. **Monthly range partitions.** Both tables `PARTITION BY RANGE COLUMNS (created_at)`, one partition per month plus an empty `pmax`; a monthly job adds next month's partition before it starts (`REORGANIZE PARTITION pmax INTO (p2027_01 VALUES LESS THAN ('2027-02-01'), pmax VALUES LESS THAN (MAXVALUE))`, instant while `pmax` is empty). Queries by time (daily rebuild, Finance, dashboard, retries, scans) then read only their months.
2. **The key changes MySQL requires.** Every primary and unique key of a partitioned table must contain the partition column, and InnoDB partitioned tables can have no foreign keys at all (neither referencing nor referenced). So:
   - `game_round`: `PRIMARY KEY (id)` becomes `PRIMARY KEY (id, created_at)`; `uk_round_request (player_id, request_id)` would become `(player_id, request_id, created_at)`, which no longer stops a repeated tap (the repeat has another `created_at`). The repeat check moves to a small unpartitioned table `round_request (player_id, request_id) PRIMARY KEY, round_id, created_at`, written in the same transaction as the round and emptied after 30 days by the housekeeping job (a tap is only repeated within seconds).
   - `wallet_transaction`: `PRIMARY KEY (id)` becomes `(id, created_at)`; `idx_tx_round` stays.
   - The foreign keys `fk_round_player` and `fk_tx_round` are dropped. The game already writes a round and its transactions in one transaction and never deletes either (triggers); a nightly check counts transactions without their round and rounds without their player instead, with an integrity alarm.
   - Lookups by id alone (`WHERE id = ?`: a round's page, retries, settlements) probe every partition's primary key: cheap with only the retention's months online (say 13 to 25), and the code can add the round's `created_at` where it has it (a transaction's round, a retry). Round ids that carry their time (UUIDv7) would let every lookup name its month; optional.
   - Converting the existing tables copies them in full (`ALTER TABLE ... PARTITION BY` is a rebuild): one maintenance window per install, or build partitioned copies and swap them with `RENAME TABLE` after catching up. `scripts/check-migrations.sh` refuses this without a `-- contract:` reason, on purpose.
3. **The archive job** (monthly, as the schema owner, like `migrate`; the application's user can't drop partitions): for each month entirely older than the online retention,
   1. refuse the month (and alert) while any of its rounds is still open (`PENDING`, `DEBITED`, `CREDIT_PENDING`, `ROLLBACK_PENDING`) or blocked and not settled (`TAMPERED` without its settlement): a disputed round stays online;
   2. export the month's rounds and transactions with every column (signatures, slots, multipliers, bonus, `meter_prev`, `odds_ref`), plus the `odds_history` rows they refer to and each player's last meter round of the month, as compressed files to a Cloud Storage bucket with a locked retention policy (write once, kept for the whole D6 period, in the jurisdiction D6 names);
   3. write a manifest: the month, counts, sums of bets and wins per currency (they must equal the month's `round_daily_totals`), the SHA-256 of each file, the key ids the signatures were made with; the manifest is signed with the records key (`RECORD_KEYS`) and its hash goes into the audit log (`ROUNDS_ARCHIVED`) and an `archive_manifest` table that stays online;
   4. read the files back from the bucket, check the hashes and counts, and verify every round's signature and win again (the round scan's check) before anything is removed;
   5. only then `ALTER TABLE ... DROP PARTITION p2026_01` on both tables (instant; it fires no delete trigger, which is why only this owner-run, audited job may do it).
4. **What changes for the integrity checks and the admin panel.**
   - The nightly full round scan covers the online months; an archived month was verified when it was archived, and a yearly job (and any dispute) re-reads its files, checks the manifest's signature and hashes, and verifies the rounds again.
   - The meter chain: the first online round of a player may point (`meter_prev`) into an archived month; the chain check accepts that when the manifest lists it as that player's last meter round.
   - Signing keys must be kept for as long as the archive: a retired key (`LEGACY_KEY_UNTIL`, rotated `ROUND_KEYS` / `RECORD_KEYS`) still verifies archived months.
   - The daily totals are not archived (they are small), so the dashboard and Finance keep every day; the wallet check (31 days) and the retries only ever touch recent months.
   - A player's history (`/api/game/history`) and the admin's round search show the online months; an older round (a dispute, a regulator) is read from the archive by restoring its month into a separate schema.
   - `audit_log` and `integrity_ledger` are kept online in full (a chain; small), unless D6 says otherwise.

### Monitoring and alerts

- **Metrics:** `/actuator/prometheus` on the admin port only (never on the public port): `plinko_play_seconds` (rounds and latency by result: `ok` or the error code), `plinko_session_seconds`, `plinko_pam_seconds` (wallet latency and errors per endpoint), `plinko_rounds_pending` and `plinko_rounds_pending_oldest_seconds`, `plinko_rounds_tampered`, `plinko_rounds_retries_stopped`, `plinko_integrity_alarms`, `plinko_rtp_measured` (last hour and today), `plinko_rounds_today`, and the database pool (`hikaricp_*`).
- **Collecting the metrics on Google Cloud** (review 7.10a; `deploy.sh deploy`): Cloud Run can't be scraped from outside, so the admin service runs Google's **Managed Service for Prometheus collector** (the `run-gmp-sidecar`, pinned by digest in `deploy/gcp/admin.yaml`) beside the backend. It reads `deploy/gcp/gmp-config.yaml` (a `RunMonitoring` config, kept in the secret `plinko-gmp-config`, mounted at `/etc/rungmp/config.yaml`) and scrapes `localhost:8081/actuator/prometheus` every 60 s inside the instance (never through IAP); the admin service's account may write metrics (`roles/monitoring.metricWriter`) and read only that extra secret. The metrics show in Cloud Monitoring as `prometheus.googleapis.com/plinko_*/...` and can be queried with PromQL (e.g. `plinko_rounds_pending_oldest_seconds`, `plinko_rtp_measured{window="today"}`). One instance is enough for the money gauges: they come from the database, not the instance. The game service's own latency metrics (`plinko_play_seconds`) would need the same sidecar in `game.yaml` (not done: nginx's log and the load test cover latency for now). `deploy.sh check` shows whether `plinko_rounds_today` arrives. The Ops Agent is for Compute Engine VMs: use it instead on a VM running the Docker stack, with a Prometheus receiver on `127.0.0.1:8081/actuator/prometheus`.
- **Logs:** every line of a game request names it and its round (`[req=... round=...]`); nginx gives each request its id, writes it in its own log and passes it to the backend, which sends it on to the PAM (`X-Request-Id`). One round can be followed from nginx to the wallet.
- **Alerts** (`deploy.sh alerts`), each with its first steps in the alert itself and sent to its **owner's channel** (review 7.10b): `ALERT_EMAIL_ONCALL`, `ALERT_EMAIL_WALLET` and `ALERT_EMAIL_FINANCE` in `config.env` (each an address or a group; an empty one falls back to `ALERT_EMAIL`). `deploy.sh alerts` makes one e-mail channel per owner ("Plinko alerts: on-call", "...: wallet", "...: finance"), keeps their addresses up to date, and points every policy at its owner's channel each time it runs, so moving an alert to another owner is a change in `config.env` and one run. The owners below are placeholders until the casino and the operator agree them:

| Alert | When | Owner (channel) | First steps |
|---|---|---|---|
| Integrity alarm | a round blocked, odds or settings edited in the database, a round failing the hourly scan | on-call engineer, then compliance (`ALERT_EMAIL_ONCALL`) | the red alarm in the admin panel says what; odds and settings: approve a restore; a blocked round: [Settling a blocked round](#settling-a-blocked-round) |
| Payment retries stopped | the wallet refused a credit or refund every time | casino wallet team, with the on-call engineer (`ALERT_EMAIL_WALLET`) | check the wallet, then Retry on the round |
| Payment waiting over 10 minutes | a player owed a win or a refund for 10 minutes | casino wallet team (`ALERT_EMAIL_WALLET`) | usually the wallet is down; it is paid automatically when the wallet answers |
| Wallet error rate | failed wallet calls over 2% of the rounds asked for in 5 minutes, and at least 5 of them (`WALLET_ALERT_RATE`, `WALLET_ALERT_MIN`) | casino wallet team (`ALERT_EMAIL_WALLET`) | check the wallet and the network to it |
| Measured RTP out of range | today's rounds (50,000 or more) paid outside the hard limits by more than 5 points | finance / game operations, the odds owner (`ALERT_EMAIL_FINANCE`) | check recent odds and bonus changes, and Finance; consider switching the game off |
| RTP away from its target | today's measured RTP (`plinko_rtp_measured{window="today"}`, from the collector) more than `RTP_TOLERANCE` points (default 2) from `TARGET_RTP` for 30 minutes, once today has `RTP_ALERT_MIN_ROUNDS` rounds (default 50,000); not set up while `TARGET_RTP` is empty | finance / game operations (`ALERT_EMAIL_FINANCE`) | as above; a few very large wins move it on a quiet day. Set the tolerance from the Monte Carlo spread of the boards in play |
| Blocked round not settled | a blocked round waiting more than 24 hours | finance / game operations, the admins who settle (`ALERT_EMAIL_FINANCE`) | settle it (below) |
| Game stopped | an admin stopped the game (Settings) | on-call engineer (`ALERT_EMAIL_ONCALL`) | check the reason in the audit log; start it again when the cause is fixed |
| Database CPU / connections | over 80% CPU, or over 200 connections, for 5 minutes | on-call engineer (`ALERT_EMAIL_ONCALL`) | check the load; a bigger `DB_TIER`, or fewer instances x pool size |
| CDN hit ratio low | under 95% of the static files (`/assets/*`, `/themes/*`) served by Cloud CDN over an hour, with at least 1,000 of them (`CDN_ALERT_RATIO`, `CDN_ALERT_MIN`) | on-call engineer (`ALERT_EMAIL_ONCALL`) | `deploy.sh check` (CDN on, cache headers, hit ratio); a deploy changes every file's address, so a short dip after one is normal |

The wallet alert is a rate: log-based counts of failed wallet calls (`plinko-wallet-errors`) over the play requests nginx logged (`plinko-play-requests`), so a busy evening and a quiet night are judged alike. The RTP-against-target alert is a PromQL condition on the collector's metrics; the CDN alert a ratio of the load balancer's own request counts by cache result. Running `deploy.sh alerts` again on an older install replaces the earlier count-based "Plinko: wallet errors" policy (more than 20 in 5 minutes) and stops using the single "Plinko alerts" channel (delete it when you like). It changes the channel of an existing policy, not its thresholds: to apply new thresholds, delete that policy in Cloud Monitoring and run `alerts` again.

**Service goals (proposals, review 7.10c; pending decisions D7 and D8).** Nothing measures these yet beyond the metrics above and the load test; once agreed, they become Cloud Monitoring SLOs on the game service with alerts on how fast the error budget is used.

| Goal | Proposed target | Measured by |
|---|---|---|
| Availability of play | 99.9% of `play` requests answered without a server error (5xx other than `WALLET_UNAVAILABLE`), over 30 days (about 43 minutes of errors a month) | nginx log or `plinko_play_seconds` by outcome |
| `play` latency | 99% under 1.5 s and 95% under 500 ms at the agreed peak (D7), wallet time included | `plinko_play_seconds`; the load test's `play` percentiles |
| Session start | 95% of `session` requests under 1 s | `plinko_session_seconds` |
| Time until playable on a phone | the first screen within 5 s on a mid-range phone over 4G (D7) | Lighthouse in the nightly run |
| Payments settled | 99.9% of wins and refunds paid within 10 minutes; none waiting over 24 hours | `plinko_rounds_pending_oldest_seconds`, the "Payment waiting" alert |
| Data loss and downtime after a failure | to be set by the casino (D8); today: point-in-time recovery to within minutes, restore in about an hour | the restore drill |

The wallet's own availability is the casino's: its errors are excluded from the game's availability, and the wallet alert covers them.

### Responsible gambling options

Set per market in **Settings -> Player features** (which ones a market needs is for the casino's compliance team, decision D11). Operators can make them stricter, which takes effect at once; only admins can loosen them (protect players less: Hold & Drop on, a later or no reality check, quiet small wins off, a shorter round time, reality check proof no longer required), and a loosening takes effect only after a second admin approves it on the Changes page. All are off by default except Hold & Drop.

| Option | What the player sees | Enforced by |
|---|---|---|
| Hold & Drop | holding the drop button keeps playing; off for markets that ban autoplay | the game |
| Session clock | time played and net result this session, always on screen | the game |
| Reality check every N minutes | the game pauses with time played and net result: **Continue playing** or **Stop playing** | the game |
| No celebration for small wins | a round that pays back less than its bet shows "PAID BACK x OF y" instead of "YOU WON" (losses disguised as wins) | the game |
| Minimum time per round | rounds at least N seconds apart (e.g. 2.5) | the server (`TOO_FAST`) |
| Require proof of the reality check | nothing more than the reality check; for markets that require proof it was shown | the server (`REALITY_CHECK_DUE`) |
| The casino's own limits | its loss, deposit and time limits and self-exclusion: the bet is refused and the player told why | the PAM (`LIMIT_REACHED`, `PLAYER_EXCLUDED`) |

**Proof that the game showed them (review 7.4).** The game reports to the server when it shows the reality check, when the player chooses **Continue playing**, and when it shows the session clock (`POST /api/game/event` with the session's bearer token, `{"type": "REALITY_CHECK_SHOWN" | "REALITY_CHECK_CONTINUED" | "SESSION_CLOCK_SHOWN", "at": <client time in ms>}`; other types are refused, at most 500 per session). Each report is kept in the `game_event` table with the server's time (the application can add and read rows, never change or remove them), and the player's admin page shows how many of each and the last. A scripted client could still skip the dialog, so with **Require proof of the reality check** on, the server itself refuses a round (409 `REALITY_CHECK_DUE`) once the reality check is due (N minutes after the session opened, or after the last "shown, then continued") and the game has not reported it; the game then shows the reality check, and play goes on once the player continues. nginx forwards `/api/game/event` like the other game calls (`deploy/nginx/nginx.conf`, same rate limit).

### Restoring a backup

Cloud SQL keeps a daily backup for 30 days and point-in-time recovery for 7 days. How much may be lost and how long the game may be down are for the casino to set (review decision D8). The restore puts the whole database back as it was, the integrity ledger with it, so the integrity checks stay consistent; what is lost is everything after the restore point, while the casino's wallet (a separate system) keeps every bet and win it took.

1. **Stop the game** (Admin -> Settings -> Stop, with the reason) so no new rounds start, and note the time.
2. **Choose the restore point** (just before the failure) and restore it to a **new** instance first (Cloud SQL, Restore, to a new instance); never over the only copy.
3. **Check it on staging:** point a staging deployment at the new instance, sign in, and look at the dashboard: no integrity alarms, the audit chain intact, the latest rounds as expected. Settings, odds and accounts are as they were at the restore point: changes approved after it must be proposed and approved again.
4. **Switch over:** point production at the restored instance (`deploy.sh` with its address), deploy, check.
5. **Find what the restore lost:** Finance -> **Check against the wallet**, from the restore point to now. "Only at the wallet" lists bets and wins the wallet moved for rounds the game no longer has: wins were already paid by the wallet, so nothing is owed for them; a bet without its win (the failure came in the middle of a round) is refunded through the casino. Rounds still pending in the game are settled by the background retry with their original transaction ids.
6. **Start the game** again, and record what was done in the audit log (the reason of the start).

The keys are not in the database: restoring needs the same `ROUND_SIGNING_KEY` and key lists (see [Keys](#keys)). The drill (`tests/restore_drill.py`) runs all of this on a test stack; do it at least twice a year.

**Drill of 2026-10-17** (14/14): backup taken after 3 rounds; then 3 more rounds, the game stopped and started, a new operator account; backup restored. Result: the game started with no integrity alarm (the ledger came back with the rows it protects), the admin signed in with the same 2FA, the 3 later rounds and the operator account were gone, the wallet kept its money, and the wallet check listed exactly the lost rounds' bets and wins. Play went on normally afterwards. The settings change and the new account had to be made again.

### Settling a blocked round

A round is blocked (`TAMPERED`) when its data fails a check: it is never paid automatically and it stays blocked for good, as evidence. The dashboard lists the blocked rounds still to settle, with how long each has waited; an alert fires after 24 hours.

1. **Investigate** (an admin, the same day): the round's page (bet, outcome, signature, wallet transactions), the audit log around it, the server logs for its round id (`round=<id>`), and the casino's wallet records for the round. Decide which happened: the round was genuine and something else failed (a bug, a check that was too strict), or its data was really changed.
2. **Propose** on the round's page: **pay** (genuine: what the round should have paid), **refund** the bet, or **close** with nothing owed (the data was changed). Write what was found.
   - **Pay** is by default exactly the round's own win, recomputed from its signed outcome (the page shows it). Any other amount, or a round whose outcome can't be trusted, is limited to the **other amounts** limit (100 per currency by default). Every payment stays within the **highest settlement** limit (1,000 per currency by default) and the round's own win limit.
   - **Refund** is exactly the round's total bet.
   - Limits are per currency, written `EUR=1000,GBP=900,SEK=11000`. **A currency without a stored limit can't be paid or refunded, only closed** (review R9): there is no fallback amount for every currency; add the currency on the Settings page first (another admin approves). The same goes for amounts other than the round's own win when no other-amounts limit is stored for the currency. They are stored settings (`settlement.maxAmounts`, `settlement.otherAmounts`), signed in the integrity ledger like every setting, and shown on **Settings → Settlement limits**. An ADMIN proposes new values there; they apply only after **another** admin approves them on the Changes page (the proposer can never approve them, even with `REQUIRE_SECOND_APPROVER=false`). Each amount must be above 0 with at most 2 decimals, each currency a real currency listed once, and an other-amounts limit can't be above the highest settlement of the same currency. Limits edited in the database fail the settings check: no payment is proposed or approved until the settings are restored. The values are for the casino and compliance to set (decision D10).
   - `SETTLEMENT_MAX_AMOUNTS` / `SETTLEMENT_OTHER_AMOUNTS` (Compose: `.env`; Google Cloud: `config.env`) are **initial values only**: the first instance that starts without the stored limits saves them (plus the platform currency at `plinko.settlement.max-amount` / `other-amount`, 1,000 / 100, if not listed) and records it in the audit log (`SETTLEMENT_LIMITS_INITIALISED`). Changing these variables afterwards changes nothing; use the Settings page.
   - **Pay** and **refund** are refused while a credit or refund of the round may already have been applied by the wallet (pending, sent with no answer recorded, or failed without the wallet clearly refusing it, e.g. a timeout). Ask the casino to look the transaction up first; until the wallet answers it definitely, only **close** is possible. Whether the wallet clearly refused is the transaction's `refused` column (review R4), never its error text: before every send of a credit or refund the game commits a "sending" mark (`in_flight_since`, which clears an earlier refusal), so a send cut off by a crash or a rolling deploy reads as "outcome unknown". The database's guard on `wallet_transaction` accepts a refusal only as the answer to a marked send, or for a transaction the game never sent.
   - The bet's record must be the round's own signed debit. A debit without a signature is accepted only for a round played before this install signed debits: the first start of the release that signs them records that time in `job_state` (`debits.signed_from`), an hour ahead for the previous release's instances during the rolling deploy (review R9). Any later unsigned debit can't be the game's, so the round is settled with the casino directly.
   - Rounds blocked within the last 90 days (`plinko.settlement.max-age-days`). Older or larger cases are settled with the casino directly.
3. **Approve:** a second admin checks the evidence and approves on the Changes page. Every approval rule applies to a settlement and to the settlement limits even with `REQUIRE_SECOND_APPROVER=false` (review R9): the proposer can never approve, and neither can an account linked to theirs (one created or reset the other's access, directly or through other accounts), so a second account made by the same admin doesn't count. Only then is the money sent, once, with a transaction id derived from the round (`manual-pay` / `manual-refund`), so a repeat never pays twice. If the wallet doesn't answer it is sent again every 5 minutes, up to `plinko.retry.max-attempts`; if the wallet refuses it (e.g. `TRANSACTION_MISMATCH`) or the attempts run out, retries stop with an alert (`SETTLEMENT_STOPPED`) and an audit entry, and only **Send to the wallet again** sends it. A stopped settlement is marked as such (`round_settlement.stopped_at`) and left out by the background retry's query itself, so however many are stopped, newer ones are still sent (review R11); **Send to the wallet again** lifts the mark, and it stops again if the wallet refuses again.
4. Everything is in the audit log (proposal, approval, payment). A settlement is signed and can't be changed in the database afterwards; one that was is never sent.

Who may investigate and approve, the limits, and how fast cases must be settled are for the casino and compliance to set (review decision D10); the values above are defaults.

### Keys

Three separate keys, so a leak of one doesn't expose the others: **round keys** (`ROUND_KEYS`: round signatures and the money they may move), **record keys** (`RECORD_KEYS`: odds, settings, admin accounts, change requests, the integrity ledger) and **encryption keys** (`ENCRYPTION_KEYS`: 2FA secrets and PAM tokens, AES-256-GCM). Each is a list `id=secret,id=secret`, newest first; ids are two lowercase letters or digits, secrets at least 32 characters.

- New data is signed or encrypted with the first key and names it: a signature is `r1.` + 61 hex digits (it fits the existing columns, 244 bits), an encrypted value `v2:e1:...`. Older keys in the list only verify and decrypt.
- Data from before key ids (64 hex digits, `v1:...`) belongs to the original `ROUND_SIGNING_KEY`, which stays required. While a list is empty, everything works with that key, exactly as before.
- On Google Cloud, `deploy.sh secrets` (and `deploy`, for an older install) creates `plinko-round-keys`, `plinko-record-keys` and `plinko-encryption-keys` with one fresh key each.
- On Docker Compose they are Docker secrets like the others: `secrets/ROUND_KEYS`, `secrets/RECORD_KEYS` and `secrets/ENCRYPTION_KEYS`, mounted in `/run/secrets` (never environment variables, which show in `docker inspect`). `scripts/generate-secrets.sh` makes them with one fresh key each. An install from before them must have the three files before its next `docker compose up` (Compose refuses to start without them): `scripts/generate-secrets.sh` adds them (one fresh key each, nothing else touched; data signed before keeps verifying with `ROUND_SIGNING_KEY`), or `scripts/secrets-from-env.sh .env` takes the lists from `.env` if they were set there (then remove those lines). Empty files also work (empty = the original key alone, as before: `touch secrets/ROUND_KEYS secrets/RECORD_KEYS secrets/ENCRYPTION_KEYS; chmod 644 secrets/*_KEYS`), to be rotated as below.
- **Retiring the original key** (`LEGACY_KEY_UNTIL`, a UTC time such as `2026-11-01T00:00:00Z`; property `plinko.keys.legacy-until`; empty by default). Needs all three lists set (the server refuses to start otherwise). At every start, admin accounts, odds tables and ledger records that pass their check but are still signed with `ROUND_SIGNING_KEY` are signed again with the current keys (audit entry `KEYS_MOVED`). The cutoff takes effect at that time, not before: a time in the future can be set in the same deploy as the lists (start-up moves what it can first, and logs what must still be settled by then). **Money still to move on the original key** (a credit or refund still owed, or a settlement waiting on the wallet, signed with it) refuses the start as soon as a cutoff is set, even one still ahead (review R6): when the time passed at runtime that money would be blocked, never paid. Settings shows how much there is ("Money still to move on it"); pay or settle it first, e.g. once the wallet is back after an outage. A credit or settlement that is blocked anyway because of its retired key raises its own alarm, `SIGNED_WITH_RETIRED_KEY` (audit `ROUND_SIGNED_WITH_RETIRED_KEY` / `SETTLEMENT_SIGNED_WITH_RETIRED_KEY`), not the tampering one: it may be genuine, and is settled with the casino. Once the time has passed, a signature in the old format (64 hex digits, no key id) verifies only on history made before that time (rounds, wallet transactions, odds versions, for showing and checking them); nothing else verifies or decrypts (`v1:`) with the original key, and **money still to move is never authorised by it**: a credit or refund signed with the original key is never paid automatically, whatever date its row carries (a row's date is not signed, so a backdated row inserted with the leaked key proves nothing; it is blocked for review instead). If something else still in use depends on it (an account that couldn't be moved, a pending change request signed with it, a 2FA secret still `v1:`), the new instance refuses to start once the cutoff has passed and lists them, and the previous revision keeps serving: settle or fix those, then deploy again.
- **Signing old records again, then removing the original key (review N8 #2).** Once the key lists are set, a background job (every 10 minutes, one instance at a time, in batches, remembering where it got to) signs every frozen record still signed with `ROUND_SIGNING_KEY` again with the current keys: rounds, confirmed wallet transactions and odds versions. Their own signature column can't change (database triggers), so the new signature is kept in `record_signature` (insert-only) and the checks accept it. A record is signed again only if it verifies now, and only before `LEGACY_KEY_UNTIL` has passed; money still to move (a pending or failed transaction) never is. Review R7:
  - A record that fails its check (e.g. a round kept `TAMPERED` as evidence), or one made at or after `LEGACY_KEY_SUSPECTED_LEAK` (it may be forged with the leaked key), is never signed again: it is kept as **evidence** (`legacy_evidence`, insert-only) and not counted as left, so it never stops the key from being removed; once the key is removed it no longer verifies, as it should not.
  - Each table is swept from the start again whenever a sweep ends, so a credit or refund confirmed after the sweep passed it is signed again on the next one. Once nothing is left the job stops for good. `LEGACY_RESIGN=false` switches it off.
  - Settings shows the key ids in use, how many records are still signed only with the original key as the job last counted them (it counts at the end of each sweep and keeps the figure with its time, review R8: the page never counts the tables itself), and how many are kept as evidence.

  When the count reaches 0 and the cutoff has passed, `ROUND_SIGNING_KEY` can be removed from the configuration (an empty value is allowed only with `LEGACY_KEY_UNTIL` and the three lists set); the server refuses to start without it while any record would still need it.
- The server logs the key ids in use when it starts (never the secrets).

**Rotating a key** (e.g. yearly, or when someone who had access leaves): add a new version of the secret with the new key in front, `r2=<new>,r1=<old>`, and deploy. New rounds use `r2`; everything signed with `r1` still verifies. Keep old keys as long as data made with them is kept (the rounds' retention period, decision D6). A signed round's own signature never changes (by design); records still signed only with the original `ROUND_SIGNING_KEY` are signed again by the background job (above), and odds, settings and admin accounts are re-signed with the new key the next time each is changed.

**Backup (escrow):** Secret Manager keeps every version. Also keep an offline copy of all three lists and `ROUND_SIGNING_KEY` (e.g. a printed or USB copy in a safe, split between two people so neither can use it alone). Without the keys: every signature fails, the game stops, and stored 2FA secrets and PAM tokens can't be read. Restoring = putting the same values back as secrets and deploying.

**If a key leaks:**
1. Put a new key in front of the leaked one's list and deploy at once (minutes). From then on nothing new can be forged with it.
2. Take the leaked key out of the list as soon as possible: anything it signed then stops verifying and is blocked, never paid (rounds from that period become blocked rounds to settle one by one). Before that, compare the rounds since the suspected leak with the casino's wallet records (Finance, the wallet reconciliation).
3. Encryption key: players' PAM tokens expire on their own; admins re-enrol 2FA (Users -> reset 2FA, with a second admin).
4. Tell the casino the same day; the regulator as the licence requires (the casino's compliance team decides, decision D9). Record what happened in the audit log notes.

**If the original `ROUND_SIGNING_KEY` leaks** (it is in no list, so steps 1-2 work differently):
1. Set all three key lists with new keys (Google Cloud: new versions of `plinko-round-keys`, `plinko-record-keys`, `plinko-encryption-keys`; Compose: `secrets/ROUND_KEYS`, `RECORD_KEYS`, `ENCRYPTION_KEYS`) and deploy. New data is signed and encrypted with them from then on, and the start-up signs accounts, odds tables and ledger records again with them (`KEYS_MOVED` in the audit log).
2. Settle the pending change requests and blocked-round settlements (approve or reject them); admins whose 2FA secret is still `v1:` re-enrol (Users -> reset 2FA, with a second admin).
3. **Stop the re-signing job first** (review R7): `LEGACY_RESIGN=false` (Google Cloud: `config.env`; Compose: `.env`) and deploy, so nothing made with the leaked key is signed again as genuine meanwhile. Then set `LEGACY_KEY_SUSPECTED_LEAK` to the time of the leak (the time of the deploy in step 1 if it isn't known) and `LEGACY_KEY_UNTIL` to the same time or later, and deploy again. If the job runs again before the cutoff passes, it never signs anything made from the leak on (that is kept as evidence); after the cutoff it signs nothing at all. If the instance refuses to start, its log lists what still depends on the old key; fix that and deploy again (the previous revision keeps serving meanwhile).
4. Steps 3-4 above (tell the casino and the regulator).

What remains: rounds and wallet transactions dated before the cutoff can still be forged with the leaked key, because history can't be signed again. That is why the cutoff should be the leak time when it is known. Compare the rounds from before the cutoff with the casino's wallet records (Finance, the wallet reconciliation), and keep `ROUND_SIGNING_KEY` set: it is still required, to verify that history.

## Security model

- **Outcomes:** decided on the server only. The client can't send slots or wins (unknown JSON fields are rejected). Every round is HMAC-signed, so editing a round in the database is detected and never paid. An hourly scan re-checks every round's signature.
- **Money:** the panel has no way to change balances, rounds or wins. Wallet calls are signed both ways and idempotent.
  - A round's credit and refund use transaction ids derived from the round and are signed by the game, including the PAM player id and the amount. A round is paid at most once, and only to its own player.
  - A refund is only sent for a round that never got an outcome.
  - Rounds interrupted by a crash are finished automatically once the round's lock has run out (6 minutes by default, longer with a slow PAM): the signed win is paid, or the bet is refunded if there was no outcome.
- **Changes:** odds and bet limits need a second admin's approval and stay inside the hard limits. The PAM connection is environment-only.
- **Protection against direct database edits:** this assumes someone has the application's database login but not `ROUND_SIGNING_KEY`.
  - **Triggers**, owned by the schema owner, stop anyone from changing a round's bet, a decided outcome, a settled round, a confirmed transaction or a player's PAM id. A round can only become settled through the allowed status steps, with a signed outcome.
  - **Signatures:** admin accounts, odds tables and change requests are signed. A signed **version ledger**, which the app can add to but never edit or delete, rejects old signed copies put back into the database. Bet limits and the game switch are version-checked too.
  - **What a mismatch does:** odds or settings that fail the check stop the game within a minute. An account that fails can't sign in or approve. A change request that fails can't be approved. Each one raises a red integrity alarm on every admin page and in the audit log. An approved change, or the recovery command, restores it.
  - **Encryption:** 2FA secrets and players' PAM session tokens are stored encrypted with AES-GCM, using a key derived from the environment.
- **Admin access:**
  - BCrypt passwords with a strength policy and mandatory TOTP 2FA (single-use codes)
  - two-step sign-in: password first, then the code on its own screen (valid 5 minutes; 3 wrong codes start over); every failed attempt counts once toward the lockout
  - lockout after 5 failures, plus a per-IP throttle
  - roles, CSRF protection and a strict CSP
  - 30-minute sessions, ended at once when the account is disabled or its role, password or 2FA changes
  - not exposed on the public port
- **Audit:** append-only and hash-chained. The application's DB user can only `SELECT`/`INSERT` on it and can't `DELETE` rounds or transactions (MySQL grants). Migrations run as a separate owner account.
- **Launch tokens:** single-use, removed from the address bar after load, never written to the nginx logs.
- **Containers:**
  - non-root, read-only root filesystem, all capabilities dropped, `no-new-privileges`, memory/PID limits
  - distroless backend, unprivileged nginx with current Alpine security updates
  - base images pinned by digest; secrets as files; migrations in a separate one-off container
  - MySQL `root` only from inside the database container (fresh installs)
  - MySQL without gosu or MySQL Shell, on an internal network with no route out
  - rate limiting on the public API

### Verification (2026-10-14)

| Check | Result |
|---|---|
| Unit tests: odds math, target-RTP builder, slot distribution, golden-ball, hot-slot and meter maths, draws, a 2-million-ball return check with golden and hot on, and two 800,000-ball meter simulations (honest player and one switching boards before the meter fills), TOTP test vectors, temporary passwords, round and wallet signatures (with bonus), crash recovery, encryption, the visitor address behind a load balancer (faked headers ignored) | 44/44 pass |
| `tests/security_e2e.py`: two-step sign-in, list filters, sorting, CSV export and injection attempts, platform currency (approval, refusing other currencies), deployment (no secrets in `docker inspect`, no owner credentials in the backend, port separation), live pages, login and 2FA, CSRF, roles, input attacks, forged sessions and PAM calls, four-eyes (incl. Set RTP), puppet accounts, linked approvers, setup period, session revocation, forgot password and recovery, Finance figures, wallet consistency, DB grants, lockout, rate limits. Tampering with the app's own DB login: credit replay, edited wins, settled rounds, payout redirection, forged rounds, edited and rolled-back odds, accounts, settings and change requests. Golden ball, hot slot and bonus meter: four-eyes, limits on bonus size, total-RTP hard limits, server-decided and signed, frozen in the database, edited settings stop the game, the meter carries over between sessions, a forged meter round pays nothing. The per-player round lock in the database: a round held by another backend instance blocks, a lock left by a crashed instance expires. Bonus meter chain: link signed and frozen, never shared by two rounds, intact with the round lock removed during parallel rounds; each ball's win adds up to the total. Public demo and the first admin's temporary password. Hold & Drop: on by default, operators switch it off, only admins on, the game is told | 256/256 pass |
| Google Cloud layout, simulated locally: backend reading secrets from per-secret folders, nginx and backend sharing one network (backend on 127.0.0.1:9080), migration job, database-users job over an encrypted connection to a fresh MySQL | a real round played; admin, mock PAM and actuator 404 through the game's nginx; rate limit keyed on the visitor the load balancer adds, faked entries ignored; app user has no rights until the migrations grant them |
| Independent re-review after the fixes (money and admin approvals) | all 10 money issues and the approval bypasses fixed; follow-up findings fixed and covered by the tests above |
| `tests/browser_check.mjs`: game under production CSP, a played round, token removed from the address, no game state in DevTools globals | no CSP violations; balance shown = server balance |
| 150,000 balls through the real API (all rows and risks, 96% tables) | actual RTP 95.43% vs 96.00% configured, inside the 95% range 94.79–97.21% (z = −0.92); rounds, wallet transactions, PAM ledger and balances agree to the cent |
| Trivy image scan (fixable HIGH/CRITICAL) | 0 in all three images. The backend's Debian base has unfixed OS-library CVEs (expat, util-linux), with no fix released yet and not used by the JVM; the web image has 1 fixable MEDIUM (zlib) |
| Trivy config scan, hadolint, secret scan | clean |
| OWASP ZAP baseline (game + admin login) | 0 failures |

Rerun the scanners regularly. New CVEs appear daily, and base images need rebuilding (`docker compose build --pull`).
