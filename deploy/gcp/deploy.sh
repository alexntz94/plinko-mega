#!/bin/bash
# Deploys Plinko to Google Cloud: Cloud Run (game + admin), Cloud SQL (MySQL 8.4, private IP only), Secret Manager,
# an HTTPS load balancer with Cloud Armor, and IAP in front of the admin panel. See "Deploy on Google Cloud" in
# the README. Run it from the repo root in Cloud Shell, one step at a time:
#
#   deploy/gcp/deploy.sh <step>            one deployment: deploy/gcp/config.env
#   deploy/gcp/deploy.sh <step> <casino>   one per casino: deploy/gcp/casinos/<casino>.env (its own project)
#
# First time:  apis  network  secrets  build  database  db-setup  migrate  deploy  lb  logs  alerts  check
#              (image signing is on by default: build sets up Binary Authorization and attests its images)
# Updates:     build  migrate  deploy  check   (every casino at once: deploy/gcp/deploy-all.sh)
# Repair:      migrate-repair   (after a migration failed half-way; then migrate again: README, Releases)
#
# Only a commit GitHub Actions has passed is built and deployed (review R1.2): build refuses uncommitted changes and a
# commit without a successful CI run, and every step that deploys images checks the commit they were built from.
# --skip-ci-check (anywhere on the line) overrides this, e.g. for an urgent fix while CI is down; it prints a warning.
#
# Secrets are made straight in Secret Manager and never written to disk or printed.
set -euo pipefail
cd "$(dirname "$0")/../.."

SKIP_CI_CHECK=no
args=()
for arg in "$@"; do
    case "$arg" in --skip-ci-check) SKIP_CI_CHECK=yes;; *) args+=("$arg");; esac
done
set -- "${args[@]+"${args[@]}"}"

CASINO=${2:-}
if [ -n "$CASINO" ]; then
    case "$CASINO" in *[!a-z0-9-]*|-*) echo "Casino names: lowercase letters, digits and - (e.g. casino-x)" >&2; exit 1;; esac
    CONFIG=deploy/gcp/casinos/$CASINO.env
    IMAGES=deploy/gcp/casinos/$CASINO.images.env
else
    CONFIG=deploy/gcp/config.env
    IMAGES=deploy/gcp/images.env
fi
[ -r "$CONFIG" ] || { echo "Copy deploy/gcp/config.env.example to $CONFIG and fill it in first." >&2; exit 1; }
# shellcheck source=/dev/null
. "$CONFIG"
: "${PROJECT_ID:?}" "${REGION:?}" "${GAME_DOMAIN:?}" "${ADMIN_EMAILS:?}" "${ADMIN_USERNAME:?}" "${PAM_BASE_URL:?}"
: "${DB_TIER:?}" "${DB_AVAILABILITY:?}" "${GAME_MAX_INSTANCES:?}"
case "$PAM_BASE_URL" in https://*) ;; *) echo "PAM_BASE_URL must start with https://" >&2; exit 1;; esac
# review 7.7d: image signing (Binary Authorization) is on unless the config says BINAUTHZ=off
BINAUTHZ=${BINAUTHZ:-on}
case "$BINAUTHZ" in on|off) ;; *) echo "BINAUTHZ must be on or off" >&2; exit 1;; esac

REPO="$REGION-docker.pkg.dev/$PROJECT_ID/plinko"
SUBNET_RANGE=10.8.0.0/24
# where Cloud Run reads the visitor's address on the admin panel (Cloud Run appends it to X-Forwarded-For)
ADMIN_CLIENT_IP_HOPS=${ADMIN_CLIENT_IP_HOPS:-1}
# where the services may connect out on port 443 (the PAM): its addresses if they are fixed, otherwise anywhere
PAM_EGRESS_RANGES=${PAM_EGRESS_RANGES:-0.0.0.0/0}
gc() { gcloud --project "$PROJECT_ID" --quiet "$@"; }
exists() { "$@" >/dev/null 2>&1; }
step() { printf '\n== %s\n' "$*"; }

# secret name in Secret Manager -> file name the containers read
SECRETS="plinko-mysql-root-password:MYSQL_ROOT_PASSWORD plinko-db-owner-password:DB_OWNER_PASSWORD
plinko-db-password:DB_PASSWORD plinko-admin-password:ADMIN_PASSWORD plinko-round-signing-key:ROUND_SIGNING_KEY
plinko-pam-api-key:PAM_API_KEY plinko-pam-secret:PAM_SECRET
plinko-round-keys:ROUND_KEYS plinko-record-keys:RECORD_KEYS plinko-encryption-keys:ENCRYPTION_KEYS"
# which service account may read which secret (and nothing else)
ACCESS="plinko-game:plinko-db-password,plinko-round-signing-key,plinko-pam-api-key,plinko-pam-secret,plinko-round-keys,plinko-record-keys,plinko-encryption-keys
plinko-admin:plinko-db-password,plinko-admin-password,plinko-round-signing-key,plinko-pam-api-key,plinko-pam-secret,plinko-round-keys,plinko-record-keys,plinko-encryption-keys
plinko-migrate:plinko-db-owner-password
plinko-db-setup:plinko-mysql-root-password,plinko-db-owner-password,plinko-db-password"

# Review R1.2: stops unless the CI workflow has a successful run on this exact commit (or --skip-ci-check was given)
require_ci() {
    if [ "$SKIP_CI_CHECK" = yes ]; then
        echo "WARNING: --skip-ci-check: $1 has not been checked against CI" >&2
        return
    fi
    command -v gh >/dev/null \
        || { echo "The CI check needs the GitHub CLI: install gh and run 'gh auth login' (or pass --skip-ci-check)." >&2; exit 1; }
    passed=$(gh run list --commit "$1" --workflow ci.yml --status success --limit 1 --json databaseId --jq length) \
        || { echo "Could not read the CI runs of $1 from GitHub (run 'gh auth login', or pass --skip-ci-check)." >&2; exit 1; }
    # anything but a count of 1 or more (none, or an answer we can't read) stops the deploy
    case "$passed" in
        [1-9]*) ;;
        *) echo "No successful CI run for commit $1: push it and wait for CI to pass (gh run list --commit $1)," >&2
           echo "or pass --skip-ci-check to deploy it anyway." >&2
           exit 1 ;;
    esac
    echo "CI passed on $1"
}

rand() { openssl rand -base64 64 | tr -d '\n/+=' | cut -c1-"$1"; }

# the instance's address of this type (PRIVATE or PRIMARY = public), empty if none
db_address() {
    gc sql instances describe plinko-db --format=json \
        | python3 -c 'import json,sys; print(" ".join(a["ipAddress"] for a in json.load(sys.stdin).get("ipAddresses", []) if a["type"] == sys.argv[1]))' "$1"
}
db_ip() { db_address PRIVATE; }

# fills the ${...} values in a deploy/gcp/*.yaml file
render() {
    [ -r "$IMAGES" ] || { echo "No images yet: run 'deploy.sh build' first." >&2; exit 1; }
    # shellcheck source=/dev/null
    . "$IMAGES"
    # images from a build before the CI check have no BUILT_COMMIT: build them again (or --skip-ci-check)
    [ -n "${BUILT_COMMIT:-}" ] || [ "$SKIP_CI_CHECK" = yes ] \
        || { echo "$IMAGES doesn't say which commit its images were built from: run 'deploy.sh build' again." >&2; exit 1; }
    require_ci "${BUILT_COMMIT:-unknown}" >&2
    DB_IP=$(db_ip)
    [ -n "$DB_IP" ] || { echo "Cloud SQL has no private IP yet: run 'deploy.sh database' first." >&2; exit 1; }
    REPORTING_TIME_ZONE=${REPORTING_TIME_ZONE:-UTC}
    LEGACY_KEY_UNTIL=${LEGACY_KEY_UNTIL:-}
    case "$LEGACY_KEY_UNTIL" in ""|[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]Z) ;;
        *) echo "LEGACY_KEY_UNTIL must be a UTC time like 2026-11-01T00:00:00Z, or empty" >&2; exit 1;; esac
    # review R7: the suspected leak of the original key (nothing made from then on is signed again), and the re-signing switch
    LEGACY_KEY_SUSPECTED_LEAK=${LEGACY_KEY_SUSPECTED_LEAK:-}
    case "$LEGACY_KEY_SUSPECTED_LEAK" in ""|[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]Z) ;;
        *) echo "LEGACY_KEY_SUSPECTED_LEAK must be a UTC time like 2026-11-01T00:00:00Z, or empty" >&2; exit 1;; esac
    LEGACY_RESIGN=${LEGACY_RESIGN:-true}
    case "$LEGACY_RESIGN" in true|false) ;; *) echo "LEGACY_RESIGN must be true or false" >&2; exit 1;; esac
    # settlement limits per currency (README, Settling a blocked round; review N3, decision D10): CUR=amount,CUR=amount
    SETTLEMENT_MAX_AMOUNTS=${SETTLEMENT_MAX_AMOUNTS:-}
    SETTLEMENT_OTHER_AMOUNTS=${SETTLEMENT_OTHER_AMOUNTS:-}
    for limits in "$SETTLEMENT_MAX_AMOUNTS" "$SETTLEMENT_OTHER_AMOUNTS"; do
        echo "$limits" | grep -Eq '^([A-Z]{3}=[0-9]+(\.[0-9]{1,2})?(,[A-Z]{3}=[0-9]+(\.[0-9]{1,2})?)*)?$' \
            || { echo "SETTLEMENT_MAX_AMOUNTS / SETTLEMENT_OTHER_AMOUNTS are written EUR=1000,GBP=900 (got '$limits')" >&2; exit 1; }
    done
    export PROJECT_ID REGION GAME_DOMAIN ADMIN_USERNAME PAM_BASE_URL GAME_MAX_INSTANCES ADMIN_CLIENT_IP_HOPS \
        DB_IP BACKEND_IMAGE WEB_IMAGE DB_SETUP_IMAGE REPORTING_TIME_ZONE LEGACY_KEY_UNTIL LEGACY_KEY_SUSPECTED_LEAK LEGACY_RESIGN SETTLEMENT_MAX_AMOUNTS SETTLEMENT_OTHER_AMOUNTS
    out=$(mktemp)
    # shellcheck disable=SC2016  # envsubst takes the names to replace, not their values
    envsubst '${PROJECT_ID} ${REGION} ${GAME_DOMAIN} ${ADMIN_USERNAME} ${PAM_BASE_URL} ${GAME_MAX_INSTANCES}
        ${ADMIN_CLIENT_IP_HOPS} ${DB_IP} ${BACKEND_IMAGE} ${WEB_IMAGE} ${DB_SETUP_IMAGE} ${REPORTING_TIME_ZONE}
        ${LEGACY_KEY_UNTIL} ${LEGACY_KEY_SUSPECTED_LEAK} ${LEGACY_RESIGN} ${SETTLEMENT_MAX_AMOUNTS} ${SETTLEMENT_OTHER_AMOUNTS}' < "deploy/gcp/$1" > "$out"
    # review 7.7d: with Binary Authorization on, the service or job runs only attested images (the project's policy)
    if [ "$BINAUTHZ" = on ]; then
        python3 - "$out" <<'PY'
import re, sys
text = open(sys.argv[1]).read()
line = "    run.googleapis.com/binary-authorization: default\n"
if "\n  annotations:\n" in text:    # the service's own annotations (2 spaces in), not the template's
    text = text.replace("\n  annotations:\n", "\n  annotations:\n" + line, 1)
else:
    text = re.sub(r"(\n  name: [^\n]*\n)", lambda m: m.group(1) + "  annotations:\n" + line, text, count=1)
open(sys.argv[1], "w").write(text)
PY
    fi
    echo "$out"
}

apis() {
    step "Turning on the Google Cloud APIs"
    gc services enable run.googleapis.com sqladmin.googleapis.com secretmanager.googleapis.com compute.googleapis.com \
        servicenetworking.googleapis.com artifactregistry.googleapis.com cloudbuild.googleapis.com iap.googleapis.com \
        logging.googleapis.com monitoring.googleapis.com
}

network() {
    step "Private network: Cloud Run subnet, private path to Cloud SQL, fixed outgoing IP for the PAM"
    exists gc compute networks describe plinko || gc compute networks create plinko --subnet-mode=custom
    exists gc compute networks subnets describe plinko-run --region "$REGION" \
        || gc compute networks subnets create plinko-run --network=plinko --region="$REGION" --range="$SUBNET_RANGE" \
            --enable-private-ip-google-access
    exists gc compute addresses describe plinko-sql-range --global \
        || gc compute addresses create plinko-sql-range --global --purpose=VPC_PEERING --prefix-length=20 --network=plinko
    gc services vpc-peerings connect --service=servicenetworking.googleapis.com --ranges=plinko-sql-range --network=plinko \
        || gc services vpc-peerings update --service=servicenetworking.googleapis.com --ranges=plinko-sql-range --network=plinko
    exists gc compute addresses describe plinko-egress --region "$REGION" || gc compute addresses create plinko-egress --region="$REGION"
    exists gc compute routers describe plinko-router --region "$REGION" \
        || gc compute routers create plinko-router --network=plinko --region="$REGION"
    exists gc compute routers nats describe plinko-nat --router=plinko-router --region "$REGION" \
        || gc compute routers nats create plinko-nat --router=plinko-router --region="$REGION" \
            --nat-custom-subnet-ip-ranges=plinko-run --nat-external-ip-pool=plinko-egress
    # outgoing traffic: only HTTPS (the PAM) and MySQL to Cloud SQL; everything else is refused
    sql_range="$(gc compute addresses describe plinko-sql-range --global --format='value(address)')/20"
    if exists gc compute firewall-rules describe plinko-allow-https-out; then
        gc compute firewall-rules update plinko-allow-https-out --destination-ranges="$PAM_EGRESS_RANGES"
    else
        gc compute firewall-rules create plinko-allow-https-out --network=plinko --direction=EGRESS --priority=1000 \
            --action=ALLOW --rules=tcp:443 --destination-ranges="$PAM_EGRESS_RANGES"
    fi
    exists gc compute firewall-rules describe plinko-allow-sql-out \
        || gc compute firewall-rules create plinko-allow-sql-out --network=plinko --direction=EGRESS --priority=1000 \
            --action=ALLOW --rules=tcp:3306 --destination-ranges="$sql_range"
    exists gc compute firewall-rules describe plinko-deny-out \
        || gc compute firewall-rules create plinko-deny-out --network=plinko --direction=EGRESS --priority=65000 \
            --action=DENY --rules=all --destination-ranges=0.0.0.0/0
    echo "Outgoing IP (give it to the PAM if it allow-lists callers): $(gc compute addresses describe plinko-egress --region "$REGION" --format='value(address)')"
}

secrets() {
    step "Secrets in Secret Manager (random values; the PAM's key and secret are asked for)"
    for pair in $SECRETS; do
        name=${pair%%:*} file=${pair#*:}
        if exists gc secrets describe "$name"; then
            echo "$name exists; kept"
            continue
        fi
        case "$file" in
            PAM_API_KEY|PAM_SECRET)
                read -rsp "Enter $file from your PAM (not shown): " value; echo
                [ ${#value} -ge 16 ] || { echo "$file must be at least 16 characters" >&2; exit 1; } ;;
            ADMIN_PASSWORD) value="$(rand 20)Aa1!" ;;
            ROUND_SIGNING_KEY) value=$(rand 64) ;;
            # separate keys per use (README, Keys): the first of each list signs or encrypts new data
            ROUND_KEYS) value="r1=$(rand 64)" ;;
            RECORD_KEYS) value="d1=$(rand 64)" ;;
            ENCRYPTION_KEYS) value="e1=$(rand 64)" ;;
            *) value=$(rand 40) ;;
        esac
        printf '%s' "$value" | gc secrets create "$name" --replication-policy=user-managed --locations="$REGION" --data-file=-
        unset value
    done

    step "Service accounts: each reads only its own secrets"
    for entry in $ACCESS; do
        account=${entry%%:*}
        email="$account@$PROJECT_ID.iam.gserviceaccount.com"
        exists gc iam service-accounts describe "$email" || gc iam service-accounts create "$account" --display-name="Plinko $account"
        IFS=, read -ra names <<< "${entry#*:}"
        for name in "${names[@]}"; do
            gc secrets add-iam-policy-binding "$name" --member="serviceAccount:$email" --role=roles/secretmanager.secretAccessor >/dev/null
        done
    done
    # older setups let the public game service read the admin password; it no longer may
    gc secrets remove-iam-policy-binding plinko-admin-password --role=roles/secretmanager.secretAccessor \
        --member="serviceAccount:plinko-game@$PROJECT_ID.iam.gserviceaccount.com" >/dev/null 2>&1 || true
    echo "The first admin password: gcloud secrets versions access latest --secret=plinko-admin-password --project=$PROJECT_ID"
}

build() {
    step "Building the images with Cloud Build"
    # the build sends the working tree, so it must be exactly the commit CI tested
    if [ "$SKIP_CI_CHECK" != yes ] && [ -n "$(git status --porcelain)" ]; then
        echo "Uncommitted changes: commit and push them, and wait for CI (or pass --skip-ci-check)." >&2
        exit 1
    fi
    commit=$(git rev-parse HEAD)
    require_ci "$commit"
    exists gc artifacts repositories describe plinko --location="$REGION" \
        || gc artifacts repositories create plinko --repository-format=docker --location="$REGION" --immutable-tags
    tag="$(git rev-parse --short HEAD 2>/dev/null || echo build)-$(date -u +%Y%m%d%H%M%S)"
    gc builds submit --region="$REGION" --config=deploy/gcp/cloudbuild.yaml --substitutions="_REPO=$REPO,_TAG=$tag" .
    # deploy by digest: the exact image that was built, whatever happens to tags later
    digest() { gc artifacts docker images describe "$REPO/$1:$tag" --format='value(image_summary.digest)'; }
    {
        echo "BACKEND_IMAGE=$REPO/backend@$(digest backend)"
        echo "WEB_IMAGE=$REPO/web@$(digest web)"
        echo "DB_SETUP_IMAGE=$REPO/db-setup@$(digest db-setup)"
        echo "BUILT_COMMIT=$commit"
    } > "$IMAGES"
    cat "$IMAGES"
    # review 7.7d: on by default; the first build sets it up (key, attestor, policy: each made only if missing)
    [ "$BINAUTHZ" != on ] || binauthz
}

# Review 7.7d: Binary Authorization (BINAUTHZ=on in the config). Each image "build" makes is attested (signed by digest
# with a Cloud KMS key that never leaves KMS), and the project's policy lets Cloud Run run only attested images, so an
# image pushed to the registry any other way, or changed after the build, is refused at deploy time. "binauthz" sets it
# up once (key, attestor, policy) and attests the current images; run it after a "build".
ATTESTOR=plinko-built
attestor_key() { echo "projects/$PROJECT_ID/locations/$REGION/keyRings/plinko/cryptoKeys/plinko-attestor/cryptoKeyVersions/1"; }

attest_images() {
    step "Attesting the images (Binary Authorization)"
    # shellcheck source=/dev/null
    . "$IMAGES"
    for image in "$BACKEND_IMAGE" "$WEB_IMAGE" "$DB_SETUP_IMAGE"; do
        if [ -n "$(gc beta container binauthz attestations list --attestor="$ATTESTOR" --attestor-project="$PROJECT_ID" \
                --artifact-url="$image" --format='value(name)' 2>/dev/null)" ]; then
            echo "already attested: $image"
            continue
        fi
        gc beta container binauthz attestations sign-and-create --artifact-url="$image" --attestor="$ATTESTOR" \
            --attestor-project="$PROJECT_ID" --keyversion="$(attestor_key)"
        echo "attested: $image"
    done
}

binauthz() {
    [ "$BINAUTHZ" = on ] || { echo "BINAUTHZ=off in $CONFIG: image signing is switched off (README, Image signing)." >&2; exit 1; }
    [ -r "$IMAGES" ] || { echo "No images yet: run 'deploy.sh build' first." >&2; exit 1; }
    step "Binary Authorization: only images built by 'deploy.sh build' run"
    gc services enable binaryauthorization.googleapis.com cloudkms.googleapis.com containeranalysis.googleapis.com
    exists gc kms keyrings describe plinko --location="$REGION" || gc kms keyrings create plinko --location="$REGION"
    exists gc kms keys describe plinko-attestor --keyring=plinko --location="$REGION" \
        || gc kms keys create plinko-attestor --keyring=plinko --location="$REGION" --purpose=asymmetric-signing \
            --default-algorithm=ec-sign-p256-sha256 --protection-level=software
    # the attestor's note (Artifact Analysis has no gcloud command for notes)
    note_url="https://containeranalysis.googleapis.com/v1/projects/$PROJECT_ID/notes/$ATTESTOR"
    if ! curl -sf -o /dev/null -H "Authorization: Bearer $(gcloud auth print-access-token)" "$note_url"; then
        curl -sf -o /dev/null -X POST -H "Authorization: Bearer $(gcloud auth print-access-token)" -H "Content-Type: application/json" \
            "https://containeranalysis.googleapis.com/v1/projects/$PROJECT_ID/notes/?noteId=$ATTESTOR" \
            -d '{"attestation": {"hint": {"human_readable_name": "Built by deploy.sh build"}}}'
    fi
    if ! exists gc container binauthz attestors describe "$ATTESTOR"; then
        gc container binauthz attestors create "$ATTESTOR" --attestation-authority-note="$ATTESTOR" \
            --attestation-authority-note-project="$PROJECT_ID"
        gc beta container binauthz attestors public-keys add --attestor="$ATTESTOR" --keyversion-project="$PROJECT_ID" \
            --keyversion-location="$REGION" --keyversion-keyring=plinko --keyversion-key=plinko-attestor --keyversion=1
    fi
    project_number=$(gc projects describe "$PROJECT_ID" --format='value(projectNumber)')
    gc container binauthz attestors add-iam-policy-binding "$ATTESTOR" \
        --member="serviceAccount:service-$project_number@gcp-sa-binaryauthorization.iam.gserviceaccount.com" \
        --role=roles/binaryauthorization.attestorsVerifier >/dev/null
    attest_images
    # the policy last, once the current images are attested: everything else needs this attestor's signature, except
    # Google's own system images and the metrics sidecar (README, Monitoring)
    file=$(mktemp)
    cat > "$file" <<POLICY
name: projects/$PROJECT_ID/policy
globalPolicyEvaluationMode: ENABLE
admissionWhitelistPatterns:
- namePattern: us-docker.pkg.dev/cloud-ops-agents-artifacts/cloud-run-gmp-sidecar/*
defaultAdmissionRule:
  evaluationMode: REQUIRE_ATTESTATION
  enforcementMode: ENFORCED_BLOCK_AND_AUDIT_LOG
  requireAttestationsBy:
  - projects/$PROJECT_ID/attestors/$ATTESTOR
POLICY
    gc container binauthz policy import "$file"
    rm -f "$file"
    echo "Policy set. Run 'deploy.sh deploy' and 'deploy.sh migrate' so the services and jobs enforce it."
}

database() {
    step "Cloud SQL: MySQL 8.4, private IP only, encrypted connections only, daily backups"
    if exists gc sql instances describe plinko-db; then
        echo "plinko-db exists; kept"
    else
        gc sql instances create plinko-db --database-version=MYSQL_8_4 --edition=ENTERPRISE --tier="$DB_TIER" \
            --region="$REGION" --availability-type="$DB_AVAILABILITY" \
            --network="projects/$PROJECT_ID/global/networks/plinko" --no-assign-ip --ssl-mode=ENCRYPTED_ONLY \
            --database-flags=local_infile=off,log_bin_trust_function_creators=on \
            --storage-type=SSD --storage-auto-increase --backup-start-time=02:00 --enable-bin-log \
            --retained-backups-count=30 --retained-transaction-log-days=7 \
            --maintenance-window-day=SUN --maintenance-window-hour=3 --deletion-protection
    fi
    # The root password goes through the Cloud SQL API from standard input: never on a command line or in gcloud's
    # own logs (which record command arguments). Safe to repeat.
    token=$(gc auth print-access-token)
    gc secrets versions access latest --secret=plinko-mysql-root-password \
        | python3 -c 'import json,sys; print(json.dumps({"name": "root", "host": "%", "password": sys.stdin.read()}))' \
        | curl -sf -X PUT -H "Authorization: Bearer $token" -H "Content-Type: application/json" --data-binary @- \
            "https://sqladmin.googleapis.com/v1/projects/$PROJECT_ID/instances/plinko-db/users?name=root&host=%25" >/dev/null \
        || { echo "Could not set the Cloud SQL root password" >&2; exit 1; }
    unset token
    echo "Root password set from Secret Manager"
    echo "Private IP: $(db_ip)"
}

run_job() {
    file=$(render "$1")
    gc run jobs replace "$file" --region="$REGION"
    rm -f "$file"
    gc run jobs execute "$2" --region="$REGION" --wait
}

db_setup() {
    step "Database users (made with SQL, so the app user gets only the grants from the migrations)"
    run_job db-setup.yaml plinko-db-setup
    echo "Grants: Cloud Run > Jobs > plinko-db-setup > Logs"
}

migrate() {
    step "Database migrations (as the schema owner)"
    # Review N11: migrations run under READ COMMITTED while the running version keeps serving players; the backfills
    # (V15 daily totals, V16 meter pointer) work in committed batches by id range, so they never lock a whole table.
    run_job migrate.yaml plinko-migrate
}

# Review R3: after a migration failed half-way (a batched backfill can't roll back by itself), removes its failed record
# so the next "migrate" runs it again; nothing else in the database changes (README, Releases: rolling back)
migrate_repair() {
    step "Repairing the migrations' record (as the schema owner)"
    file=$(render migrate.yaml)
    gc run jobs replace "$file" --region="$REGION"
    rm -f "$file"
    gc run jobs execute plinko-migrate --region="$REGION" --wait \
        --args=-Dloader.main=com.plinko.migration.Migrator,-cp,/app/app.jar,org.springframework.boot.loader.launch.PropertiesLauncher,repair
}

deploy() {
    # an install from before separate keys: make them first (data signed before keeps verifying with the original key)
    for key in plinko-round-keys plinko-record-keys plinko-encryption-keys; do
        exists gc secrets describe "$key" || { secrets; break; }
    done
    metrics_collector
    step "Admin panel (behind IAP)"
    file=$(render admin.yaml)
    gc run services replace "$file" --region="$REGION"
    rm -f "$file"
    project_number=$(gc projects describe "$PROJECT_ID" --format='value(projectNumber)')
    gc beta services identity create --service=iap.googleapis.com >/dev/null
    gc run services add-iam-policy-binding plinko-admin --region="$REGION" \
        --member="serviceAccount:service-$project_number@gcp-sa-iap.iam.gserviceaccount.com" --role=roles/run.invoker >/dev/null
    # IAP lets in exactly the Google accounts in ADMIN_EMAILS: added ones get access, removed ones lose it
    IFS=, read -ra emails <<< "${ADMIN_EMAILS// /}"
    wanted=" ${emails[*]/#/user:} "
    for email in "${emails[@]}"; do
        gc iap web add-iam-policy-binding --resource-type=cloud-run --service=plinko-admin --region="$REGION" \
            --member="user:$email" --role=roles/iap.httpsResourceAccessor >/dev/null
    done
    current=$(gc iap web get-iam-policy --resource-type=cloud-run --service=plinko-admin --region="$REGION" --format=json \
        | python3 -c 'import json,sys; print(" ".join(m for b in json.load(sys.stdin).get("bindings", []) if b["role"] == "roles/iap.httpsResourceAccessor" for m in b["members"]))')
    for member in $current; do
        if [[ "$wanted" != *" $member "* ]]; then
            gc iap web remove-iam-policy-binding --resource-type=cloud-run --service=plinko-admin --region="$REGION" \
                --member="$member" --role=roles/iap.httpsResourceAccessor >/dev/null
            echo "Removed admin panel access: $member"
        fi
    done

    step "Game (nginx + backend)"
    file=$(render game.yaml)
    gc run services replace "$file" --region="$REGION"
    rm -f "$file"
    # anyone may call it, but its ingress setting only lets the load balancer through
    gc run services add-iam-policy-binding plinko-game --region="$REGION" --member=allUsers --role=roles/run.invoker >/dev/null
    # review N12: installs updated with the "Updates" steps (never "lb" again) get the CDN settings too
    if exists gc compute backend-services describe plinko-game-be --global; then
        cdn
        static_paths
    else
        echo "No load balancer yet: run 'deploy.sh lb' (it also turns on Cloud CDN)."
    fi
    echo "Admin panel: $(gc run services describe plinko-admin --region="$REGION" --format='value(status.url)')/admin"
}

# Cloud CDN (review 4.1, N7, N12): the edge keeps what nginx marks as cacheable (the game's fingerprinted scripts, and
# theme files and sounds asked for with ?v=<fingerprint>: "public, max-age=31536000, immutable") and nothing else;
# only "v" is part of the cache key, so random query strings can't bypass it, and the page itself (no-store: launch
# links carry a token) is never kept. The edge also compresses text for browsers that accept it (nginx compresses
# too, gzip_proxied any). The same values every run, so it is safe to repeat: both "lb" and "deploy" run it.
cdn() {
    step "Cloud CDN on the game's backend service (cache and compression)"
    gc compute backend-services update plinko-game-be --global --enable-cdn --cache-mode=USE_ORIGIN_HEADERS \
        --cache-key-include-query-string --cache-key-query-string-whitelist=v --compression-mode=AUTOMATIC >/dev/null
    echo "Cloud CDN: $(gc compute backend-services describe plinko-game-be --global --format='value(enableCDN,compressionMode)')"
}

# Review 5.6 #6 / 7.10a: the static files (the game's scripts and sounds under /assets/, the theme files under /themes/)
# get path rules of their own in the URL map, to the same backend service, so Cloud Monitoring counts their requests
# apart (the matched_url_path_rule label) and the CDN hit ratio is measured on what the CDN should keep, not on the
# page and the game API (never cached). Nothing changes for players. Safe to repeat.
static_paths() {
    exists gc compute url-maps describe plinko-map --global || return 0
    if ! gc compute url-maps describe plinko-map --global --format='value(pathMatchers[].name)' | tr ';' '\n' | grep -qx static; then
        gc compute url-maps add-path-matcher plinko-map --global --path-matcher-name=static --default-service=plinko-game-be \
            --path-rules="/assets/*=plinko-game-be,/themes/*=plinko-game-be" --new-hosts="$GAME_DOMAIN" >/dev/null
    fi
    echo "URL map path rules for the static files: /assets/*, /themes/* (for the CDN hit ratio)"
}

# Review 7.10a: the admin service runs Google's Managed Service for Prometheus collector beside the backend (admin.yaml);
# it reads what to scrape from this secret (deploy/gcp/gmp-config.yaml: the admin port's /actuator/prometheus) and
# writes to Cloud Monitoring as the admin service's account. Safe to repeat; a changed file becomes a new version.
metrics_collector() {
    step "Metrics collector settings (Managed Service for Prometheus, admin service)"
    local email="plinko-admin@$PROJECT_ID.iam.gserviceaccount.com"
    if ! exists gc secrets describe plinko-gmp-config; then
        gc secrets create plinko-gmp-config --replication-policy=user-managed --locations="$REGION" --data-file=deploy/gcp/gmp-config.yaml
    elif [ "$(gc secrets versions access latest --secret=plinko-gmp-config)" != "$(cat deploy/gcp/gmp-config.yaml)" ]; then
        gc secrets versions add plinko-gmp-config --data-file=deploy/gcp/gmp-config.yaml >/dev/null
    fi
    gc secrets add-iam-policy-binding plinko-gmp-config --member="serviceAccount:$email" --role=roles/secretmanager.secretAccessor >/dev/null
    gc projects add-iam-policy-binding "$PROJECT_ID" --member="serviceAccount:$email" --role=roles/monitoring.metricWriter \
        --condition=None >/dev/null
    echo "Collector: scrapes the admin port's /actuator/prometheus every 60 s into Cloud Monitoring (prometheus.googleapis.com/plinko_*)"
}

lb() {
    step "HTTPS load balancer for $GAME_DOMAIN, with Cloud Armor"
    exists gc compute addresses describe plinko-ip --global || gc compute addresses create plinko-ip --global
    exists gc compute network-endpoint-groups describe plinko-game-neg --region="$REGION" \
        || gc compute network-endpoint-groups create plinko-game-neg --region="$REGION" \
            --network-endpoint-type=serverless --cloud-run-service=plinko-game
    if ! exists gc compute security-policies describe plinko-armor; then
        gc compute security-policies create plinko-armor --description="Plinko game: per-address rate limit and web attack rules"
        gc compute security-policies update plinko-armor --json-parsing=STANDARD
    fi
    # each rule on its own, so a rule that failed once is created on the next run
    priority=100
    for rule in sqli xss lfi rfi rce protocolattack scannerdetection methodenforcement; do
        exists gc compute security-policies rules describe $priority --security-policy=plinko-armor \
            || gc compute security-policies rules create $priority --security-policy=plinko-armor --action=deny-403 \
                --expression="evaluatePreconfiguredWaf('$rule-v33-stable', {'sensitivity': 1})"
        priority=$((priority + 10))
    done
    # per visitor address: 300 requests a minute (a game load is ~60); over that, refused for the minute
    exists gc compute security-policies rules describe 1000 --security-policy=plinko-armor \
        || gc compute security-policies rules create 1000 --security-policy=plinko-armor --expression="true" \
            --action=throttle --rate-limit-threshold-count=300 --rate-limit-threshold-interval-sec=60 \
            --conform-action=allow --exceed-action=deny-429 --enforce-on-key=IP
    if ! exists gc compute backend-services describe plinko-game-be --global; then
        gc compute backend-services create plinko-game-be --global --load-balancing-scheme=EXTERNAL_MANAGED \
            --security-policy=plinko-armor --no-enable-logging \
            --custom-response-header='Strict-Transport-Security: max-age=31536000; includeSubDomains'
        gc compute backend-services add-backend plinko-game-be --global \
            --network-endpoint-group=plinko-game-neg --network-endpoint-group-region="$REGION"
    fi
    cdn
    exists gc compute url-maps describe plinko-map --global || gc compute url-maps create plinko-map --default-service=plinko-game-be --global
    static_paths
    exists gc compute ssl-certificates describe plinko-cert --global \
        || gc compute ssl-certificates create plinko-cert --domains="$GAME_DOMAIN" --global
    exists gc compute ssl-policies describe plinko-tls \
        || gc compute ssl-policies create plinko-tls --profile=RESTRICTED --min-tls-version=1.2 --global
    exists gc compute target-https-proxies describe plinko-https --global \
        || gc compute target-https-proxies create plinko-https --url-map=plinko-map --ssl-certificates=plinko-cert \
            --ssl-policy=plinko-tls --global
    exists gc compute forwarding-rules describe plinko-https --global \
        || gc compute forwarding-rules create plinko-https --global --load-balancing-scheme=EXTERNAL_MANAGED \
            --address=plinko-ip --target-https-proxy=plinko-https --ports=443
    # plain http:// only redirects to https://
    if ! exists gc compute url-maps describe plinko-redirect --global; then
        printf 'name: plinko-redirect\ndefaultUrlRedirect:\n  httpsRedirect: true\n  redirectResponseCode: MOVED_PERMANENTLY_DEFAULT\n' \
            | gc compute url-maps import plinko-redirect --global --source=-
    fi
    exists gc compute target-http-proxies describe plinko-http --global \
        || gc compute target-http-proxies create plinko-http --url-map=plinko-redirect --global
    exists gc compute forwarding-rules describe plinko-http --global \
        || gc compute forwarding-rules create plinko-http --global --load-balancing-scheme=EXTERNAL_MANAGED \
            --address=plinko-ip --target-http-proxy=plinko-http --ports=80
    echo "Point the DNS A record of $GAME_DOMAIN at $(gc compute addresses describe plinko-ip --global --format='value(address)')"
    echo "The certificate becomes ACTIVE up to an hour after DNS points here: deploy.sh check"
}

logs() {
    step "Keep launch tokens out of Cloud Logging"
    # Cloud Run's own request log records full URLs, and the game's URL carries the player's launch token.
    # nginx still logs every request, path only.
    if ! gc logging sinks describe _Default --format='value(exclusions[].name)' | grep -qw plinko-game-urls; then
        gc logging sinks update _Default --add-exclusion=name=plinko-game-urls,filter='logName:"run.googleapis.com%2Frequests" AND resource.type="cloud_run_revision" AND resource.labels.service_name="plinko-game"'
    fi
    echo "Excluded: Cloud Run request log of plinko-game"
}

# Review 7.10: e-mail alerts, each with a short runbook. The game writes a clear line to its log for each event that
# needs a person (blocked rounds, edited odds or settings, payments that stopped retrying or wait too long, RTP out of
# range); log-based metrics count those lines. The metrics the admin service's collector sends (7.10a: RTP against its
# target) and the load balancer's own (the CDN hit ratio) drive the rest. Review 7.10b: each alert goes to its owner's
# channel (ALERT_EMAIL_ONCALL, ALERT_EMAIL_WALLET, ALERT_EMAIL_FINANCE; each defaults to ALERT_EMAIL). Run again any
# time: what is missing is created, and every policy is pointed at its owner's channel again.
alerts() {
    : "${ALERT_EMAIL:?set ALERT_EMAIL in $CONFIG (who gets the alerts that have no owner address of their own)}"
    step "Alert channels per owner"
    # one e-mail channel per owner, its address kept up to date
    channel_for() {   # owner, address
        local name="Plinko alerts: $1" found
        found=$(gc beta monitoring channels list --filter="displayName=\"$name\"" --format='value(name)' | head -1)
        if [ -z "$found" ]; then
            found=$(gc beta monitoring channels create --display-name="$name" --type=email \
                --channel-labels=email_address="$2" --format='value(name)')
        else
            gc beta monitoring channels update "$found" --update-channel-labels=email_address="$2" >/dev/null
        fi
        echo "$found"
    }
    oncall=$(channel_for on-call "${ALERT_EMAIL_ONCALL:-$ALERT_EMAIL}")
    wallet=$(channel_for wallet "${ALERT_EMAIL_WALLET:-$ALERT_EMAIL}")
    finance=$(channel_for finance "${ALERT_EMAIL_FINANCE:-$ALERT_EMAIL}")
    echo "on-call: ${ALERT_EMAIL_ONCALL:-$ALERT_EMAIL}   wallet: ${ALERT_EMAIL_WALLET:-$ALERT_EMAIL}   finance: ${ALERT_EMAIL_FINANCE:-$ALERT_EMAIL}"

    step "Log-based metrics"
    run='resource.type="cloud_run_revision"'
    metric() {   # name, filter on the log line, description
        exists gc logging metrics describe "$1" || gc logging metrics create "$1" --description="$3" --log-filter="$run AND ($2)"
    }
    metric plinko-integrity 'textPayload:"TAMPERED" OR textPayload:"INTEGRITY FAILURE" OR textPayload:"ROUND INTEGRITY"' \
        "Blocked rounds, odds or settings edited in the database, rounds failing the hourly scan"
    metric plinko-retries-stopped 'textPayload:"automatic retries stopped"' "A credit or refund the wallet kept refusing"
    metric plinko-payment-waiting 'textPayload:"ALERT PENDING_PAYMENT_OLD"' "A credit or refund waiting more than 10 minutes"
    metric plinko-rtp 'textPayload:"ALERT RTP_OUT_OF_RANGE"' "Today's measured RTP outside the hard limits"
    metric plinko-blocked-old 'textPayload:"ALERT BLOCKED_ROUND_OLD"' "A blocked round waiting more than 24 hours for its settlement"
    metric plinko-game-stopped 'textPayload:"ALERT GAME_STOPPED"' "An admin stopped the game"
    metric plinko-wallet-errors 'textPayload:"Credit failed for round" OR (textPayload:"PAM" AND textPayload:"failed:") OR textPayload:"missing or invalid signature"' \
        "Wallet calls that failed"
    # every round asked for, whatever its answer (nginx's log line of the play request): the wallet alert's denominator
    metric plinko-play-requests 'textPayload:"\"POST /api/game/play\""' "Rounds asked for (play requests through nginx)"

    step "Alert policies"
    # creates the policy from the JSON on stdin, or points an existing one at its owner's channel (an existing policy's
    # condition is left as it is: delete the policy in Cloud Monitoring to have it made again with new thresholds)
    apply_policy() {   # name, channel, owner
        local found file
        found=$(gc alpha monitoring policies list --filter="displayName=\"$1\"" --format='value(name)' | head -1)
        file=$(mktemp)
        cat > "$file"
        if [ -z "$found" ]; then
            gc alpha monitoring policies create --policy-from-file="$file" >/dev/null
        else
            gc alpha monitoring policies update "$found" --set-notification-channels="$2" >/dev/null
        fi
        rm -f "$file"
        printf '%-48s -> %s\n' "$1" "$3"
    }
    policy() {   # name, channel, owner, metric filter, threshold, window (s), aligner, runbook
        NAME="$1" CHANNEL="$2" FILTER="$4" THRESHOLD="$5" WINDOW="$6" ALIGNER="$7" RUNBOOK="$8" python3 - <<'PY' | apply_policy "$1" "$2" "$3"
import json, os
e = os.environ
print(json.dumps({"displayName": e["NAME"], "combiner": "OR", "notificationChannels": [e["CHANNEL"]],
    "documentation": {"content": e["RUNBOOK"], "mimeType": "text/markdown"},
    "conditions": [{"displayName": e["NAME"], "conditionThreshold": {"filter": e["FILTER"], "comparison": "COMPARISON_GT",
        "thresholdValue": float(e["THRESHOLD"]), "duration": "0s" if e["ALIGNER"] == "ALIGN_SUM" else e["WINDOW"] + "s",
        "aggregations": [{"alignmentPeriod": e["WINDOW"] + "s", "perSeriesAligner": e["ALIGNER"], "crossSeriesReducer": "REDUCE_SUM"
                          if e["ALIGNER"] == "ALIGN_SUM" else "REDUCE_MEAN"}]}}]}))
PY
    }
    # name, channel, owner, numerator filter, denominator filter, comparison, ratio, window (s), minimum count of the
    # denominator, runbook: the ratio of two counts over the window past the ratio, while the denominator is over the
    # minimum (both conditions; a quiet hour doesn't page anyone)
    ratio_policy() {
        NAME="$1" CHANNEL="$2" FILTER="$4" DENOMINATOR="$5" COMPARISON="$6" RATIO="$7" WINDOW="$8" MINIMUM="$9" RUNBOOK="${10}" python3 - <<'PY' | apply_policy "$1" "$2" "$3"
import json, os
e = os.environ
window = [{"alignmentPeriod": e["WINDOW"] + "s", "perSeriesAligner": "ALIGN_SUM", "crossSeriesReducer": "REDUCE_SUM"}]
print(json.dumps({"displayName": e["NAME"], "combiner": "AND", "notificationChannels": [e["CHANNEL"]],
    "documentation": {"content": e["RUNBOOK"], "mimeType": "text/markdown"},
    "conditions": [
        {"displayName": "ratio " + ("under " if e["COMPARISON"] == "COMPARISON_LT" else "over ") + e["RATIO"], "conditionThreshold": {
            "filter": e["FILTER"], "aggregations": window, "denominatorFilter": e["DENOMINATOR"], "denominatorAggregations": window,
            "comparison": e["COMPARISON"], "thresholdValue": float(e["RATIO"]), "duration": "0s"}},
        {"displayName": "at least " + e["MINIMUM"] + " in the window", "conditionThreshold": {
            "filter": e["DENOMINATOR"] if e["COMPARISON"] == "COMPARISON_LT" else e["FILTER"], "aggregations": window,
            "comparison": "COMPARISON_GT", "thresholdValue": float(e["MINIMUM"]) - 1, "duration": "0s"}}]}))
PY
    }
    # a PromQL condition on the metrics the admin service's collector sends (Managed Service for Prometheus)
    promql_policy() {   # name, channel, owner, query, for (s), runbook
        NAME="$1" CHANNEL="$2" QUERY="$4" FOR="$5" RUNBOOK="$6" python3 - <<'PY' | apply_policy "$1" "$2" "$3"
import json, os
e = os.environ
print(json.dumps({"displayName": e["NAME"], "combiner": "OR", "notificationChannels": [e["CHANNEL"]],
    "documentation": {"content": e["RUNBOOK"], "mimeType": "text/markdown"},
    "conditions": [{"displayName": e["NAME"], "conditionPrometheusQueryLanguage": {
        "query": e["QUERY"], "duration": e["FOR"] + "s", "evaluationInterval": "60s"}}]}))
PY
    }
    user() { echo "metric.type=\"logging.googleapis.com/user/$1\" AND $run"; }
    policy "Plinko: integrity alarm" "$oncall" on-call "$(user plinko-integrity)" 0 60 ALIGN_SUM \
        "A round was blocked, or odds / settings / rounds were changed outside the admin panel. Nothing was paid. Open the admin panel: the red alarm says what. Odds or settings: the game is stopped until an approved restore. Blocked round: follow the dispute process (README, Security model)."
    policy "Plinko: payment retries stopped" "$wallet" wallet "$(user plinko-retries-stopped)" 0 60 ALIGN_SUM \
        "The wallet refused a credit or refund every time. Check the wallet (its status, the game's API key), then press Retry on the round (Rounds, Credit pending / Rollback pending)."
    policy "Plinko: payment waiting over 10 minutes" "$wallet" wallet "$(user plinko-payment-waiting)" 0 60 ALIGN_SUM \
        "A player has been owed a win or a refund for 10 minutes. Usually the wallet is down or slow: check it. Rounds pays it automatically when the wallet answers; Retry on the round to try at once."
    policy "Plinko: game stopped" "$oncall" on-call "$(user plinko-game-stopped)" 0 60 ALIGN_SUM \
        "An admin stopped the game (the reason is in the alert's log line and in Admin -> Audit log). Players can't start rounds until it is started again in Settings."
    policy "Plinko: blocked round not settled" "$finance" finance "$(user plinko-blocked-old)" 0 3600 ALIGN_SUM \
        "A blocked (TAMPERED) round has waited more than 24 hours. Admin -> Dashboard lists it. Investigate (README, Settling a blocked round), then propose pay, refund or close on the round's page; a second admin approves."
    policy "Plinko: measured RTP out of range" "$finance" finance "$(user plinko-rtp)" 0 60 ALIGN_SUM \
        "Today's rounds paid outside the hard RTP limits (over 50,000 rounds). Check Odds and Bonuses for a recent change, and Finance for the day. Consider switching the game off (Settings) while it is checked."
    # review 7.10a: today's measured RTP (plinko_rtp_measured, sent by the admin service's collector) against the target
    # the odds were set for (TARGET_RTP), not only the hard limits; judged once there are enough rounds for chance to
    # even out, and only after it has stayed away for 30 minutes
    if [ -n "${TARGET_RTP:-}" ]; then
        promql_policy "Plinko: RTP away from its target" "$finance" finance \
            "abs(avg(plinko_rtp_measured{window=\"today\"}) - ${TARGET_RTP}) > ${RTP_TOLERANCE:-2} and on() avg(plinko_rounds_today) > ${RTP_ALERT_MIN_ROUNDS:-50000}" 1800 \
            "Today's measured RTP has been more than ${RTP_TOLERANCE:-2} points from the target ${TARGET_RTP}% for 30 minutes (over ${RTP_ALERT_MIN_ROUNDS:-50000} rounds). Check Odds and Bonuses for a recent change and Finance for the day (a few very large wins also move it on a quiet day). Consider switching the game off (Settings) while it is checked."
    else
        echo "No TARGET_RTP in $CONFIG: the RTP-against-target alert is not set up (the hard-limit one is)."
    fi
    # review 7.10b: a rate, not a count: failed wallet calls over WALLET_ALERT_RATE of the rounds asked for in 5 minutes
    # (default 2%), and at least WALLET_ALERT_MIN of them (default 5, so a quiet hour's one failure doesn't page anyone)
    ratio_policy "Plinko: wallet error rate" "$wallet" wallet "$(user plinko-wallet-errors)" "$(user plinko-play-requests)" \
        COMPARISON_GT "${WALLET_ALERT_RATE:-0.02}" 300 "${WALLET_ALERT_MIN:-5}" \
        "Failed wallet calls are over ${WALLET_ALERT_RATE:-0.02} of the rounds in 5 minutes. Players see 'wallet unavailable'; bets are refunded and wins retried automatically. Check the wallet's status and the network path to it, then tell the casino's wallet team."
    # the count-based alert it replaces (more than 20 failures in 5 minutes, whatever the traffic)
    old=$(gc alpha monitoring policies list --filter='displayName="Plinko: wallet errors"' --format='value(name)')
    [ -z "$old" ] || gc alpha monitoring policies delete "$old" >/dev/null
    # review 5.6 #6 / 7.10a: the CDN hit ratio of the static files (the URL map's path rules /assets/* and /themes/*, see
    # static_paths) under CDN_ALERT_RATIO (default 95%) over an hour with at least CDN_ALERT_MIN requests (default 1,000)
    lb_requests='metric.type="loadbalancing.googleapis.com/https/request_count" AND resource.type="https_lb_rule" AND resource.label.url_map_name="plinko-map" AND resource.label.matched_url_path_rule=one_of("/assets/*","/themes/*")'
    ratio_policy "Plinko: CDN hit ratio low" "$oncall" on-call \
        "$lb_requests AND metric.label.cache_result=one_of(\"HIT\",\"PARTIAL_HIT\")" "$lb_requests" \
        COMPARISON_LT "${CDN_ALERT_RATIO:-0.95}" 3600 "${CDN_ALERT_MIN:-1000}" \
        "Over the last hour under ${CDN_ALERT_RATIO:-0.95} of the game's static files (scripts, theme files, sounds) came from Cloud CDN; the rest reached the game service. Check 'deploy.sh check' (Cloud CDN on, the cache headers, the hit ratio), a deploy that changed every file's address (new fingerprints: a short dip is normal), and the backend service's CDN settings (cache mode, cache key ?v)."
    policy "Plinko: database CPU over 80%" "$oncall" on-call 'metric.type="cloudsql.googleapis.com/database/cpu/utilization" AND resource.type="cloudsql_database"' 0.8 300 ALIGN_MEAN \
        "The database has been busy for 5 minutes. Check the load (rounds per second) and slow queries; consider a bigger DB_TIER."
    policy "Plinko: database connections high" "$oncall" on-call 'metric.type="cloudsql.googleapis.com/database/network/connections" AND resource.type="cloudsql_database"' 200 300 ALIGN_MEAN \
        "Over 200 database connections for 5 minutes (game instances x 12 + admin 5). Check GAME_MAX_INSTANCES against the database's max_connections."
    # the single channel of earlier installs, replaced by the channels per owner above
    old=$(gc beta monitoring channels list --filter='displayName="Plinko alerts"' --format='value(name)' | head -1)
    [ -z "$old" ] || echo "The earlier single channel 'Plinko alerts' is no longer used: delete it in Cloud Monitoring when you like."
}

check() {
    step "Checks"
    game_url=$(gc run services describe plinko-game --region="$REGION" --format='value(status.url)')
    admin_url=$(gc run services describe plinko-admin --region="$REGION" --format='value(status.url)')
    code() { curl -s -o /dev/null -w '%{http_code}' --max-time 15 "$1"; }
    result() { printf '%-62s %s\n' "$1" "$2"; }
    result "game's run.app address refuses visitors (expect 404):" "$(code "$game_url/")"
    result "admin panel without a Google sign-in (expect 302 or 401):" "$(code "$admin_url/admin/login")"
    result "game through the load balancer (expect 200):" "$(code "https://$GAME_DOMAIN/")"
    result "admin pages on the game address (expect 404):" "$(code "https://$GAME_DOMAIN/admin/login")"
    result "plain http redirects to https (expect 301):" "$(code "http://$GAME_DOMAIN/")"
    result "HSTS header:" "$(curl -sI --max-time 15 "https://$GAME_DOMAIN/" | grep -ci strict-transport-security || true)"
    result "certificate:" "$(gc compute ssl-certificates describe plinko-cert --global --format='value(managed.status)')"
    result "Cloud SQL public IP (expect none):" "$(db_address PRIMARY)"
    result "Cloud SQL users (expect root, plinko_owner, plinko_app):" "$(gc sql users list --instance=plinko-db --format='value(name)' | tr '\n' ' ')"
    # review N7 / N12: compression and the CDN, seen through the load balancer (README, "Caching and compression")
    cdn_state=$(gc compute backend-services describe plinko-game-be --global --format='value(enableCDN)' 2>/dev/null || true)
    result "Cloud CDN on (expect True):" "${cdn_state:-no load balancer}"
    [ "$cdn_state" = True ] || echo "WARNING: Cloud CDN is off: run 'deploy.sh deploy' (or 'lb') to turn it on." >&2
    script=$(curl -s --max-time 15 "https://$GAME_DOMAIN/" | grep -o '/assets/[^"]*\.js' | head -1 || true)
    if [ -n "$script" ]; then
        headers() { curl -s -o /dev/null -D - --max-time 15 -H 'Accept-Encoding: gzip' "https://$GAME_DOMAIN$script" | tr -d '\r' || true; }
        headers >/dev/null    # the first request fills the edge; the second should come from it
        seen=$(headers)
        result "game script compressed (expect gzip):" "$(echo "$seen" | sed -n 's/^[Cc]ontent-[Ee]ncoding: *//p')"
        result "game script cache header (expect public, ..., immutable):" "$(echo "$seen" | sed -n 's/^[Cc]ache-[Cc]ontrol: *//p')"
        result "game script from the CDN (expect an Age, in seconds):" "$(echo "$seen" | sed -n 's/^[Aa]ge: *//p')"
    else
        result "game script compressed and cached:" "page not reachable yet"
    fi
    # review 5.6 #6 / 7.10a: the last hour's CDN hit ratio from Cloud Monitoring (the load balancer's request count by
    # cache result), for the static files (the URL map's path rules, static_paths) and for every request; and whether
    # the admin service's collector is sending the game's metrics
    MONITORING_TOKEN=$(gcloud auth print-access-token) python3 - "$PROJECT_ID" <<'PY' || echo "WARNING: could not read Cloud Monitoring" >&2
import json, os, sys, time, urllib.parse, urllib.request
project = sys.argv[1]
now = time.time()
def series(flt, group, seconds=3600, aligner="ALIGN_SUM"):
    q = {"filter": flt, "interval.endTime": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(now)),
         "interval.startTime": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(now - seconds)),
         "aggregation.alignmentPeriod": f"{seconds}s", "aggregation.perSeriesAligner": aligner, "aggregation.crossSeriesReducer": "REDUCE_SUM"}
    url = f"https://monitoring.googleapis.com/v3/projects/{project}/timeSeries?" + urllib.parse.urlencode(q) + "".join(
        "&aggregation.groupByFields=" + urllib.parse.quote(g) for g in group)
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + os.environ["MONITORING_TOKEN"]})
    return json.load(urllib.request.urlopen(req, timeout=30)).get("timeSeries", [])
def value(point):
    v = point["value"]
    return float(v.get("int64Value", v.get("doubleValue", 0)))
rows = series('metric.type="loadbalancing.googleapis.com/https/request_count" AND resource.type="https_lb_rule" '
              'AND resource.label.url_map_name="plinko-map"', ["metric.label.cache_result", "resource.label.matched_url_path_rule"])
counts = {}
for s in rows:
    static = s["resource"]["labels"].get("matched_url_path_rule") in ("/assets/*", "/themes/*")
    result = s["metric"]["labels"].get("cache_result", "UNKNOWN")
    for scope in (("static",) if static else ()) + ("all",):
        counts.setdefault(scope, {}).setdefault(result, 0)
        counts[scope][result] += sum(value(p) for p in s.get("points", []))
for scope, label in (("static", "CDN hit ratio, static files, last hour (expect >= 95%):"), ("all", "CDN hit ratio, every request, last hour:")):
    c = counts.get(scope, {})
    total = sum(c.values())
    hits = c.get("HIT", 0) + c.get("PARTIAL_HIT", 0)
    shown = f"{100 * hits / total:.1f}% of {int(total)} requests {json.dumps({k: int(v) for k, v in sorted(c.items())})}" if total else "no requests in the last hour"
    print(f"{label:<62} {shown}")
    if scope == "static" and total >= 1000 and hits / total < 0.95:
        print("WARNING: under 95% of the static files came from Cloud CDN in the last hour (README, Caching and compression)", file=sys.stderr)
backend = sum(value(p) for s in series('metric.type="loadbalancing.googleapis.com/https/backend_request_count" AND resource.type="https_lb_rule" '
                                         'AND resource.label.url_map_name="plinko-map"', []) for p in s.get("points", []))
print(f"{'requests that reached the game service, last hour:':<62} {int(backend)}")
rounds = series('metric.type="prometheus.googleapis.com/plinko_rounds_today/gauge"', [], seconds=600, aligner="ALIGN_MAX")
seen = [value(p) for s in rounds for p in s.get("points", [])]
print(f"{'game metrics collected (plinko_rounds_today, last 10 min):':<62} {int(max(seen)) if seen else 'none: check the admin service collector'}")
PY
}

case "${1:-}" in
    apis) apis ;;
    network) network ;;
    secrets) secrets ;;
    build) build ;;
    database) database ;;
    db-setup) db_setup ;;
    migrate) migrate ;;
    migrate-repair) migrate_repair ;;
    deploy) deploy ;;
    lb) lb ;;
    logs) logs ;;
    alerts) alerts ;;
    check) check ;;
    binauthz) binauthz ;;
    *) sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
