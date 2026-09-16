#!/usr/bin/env bash
# Builds the fat JAR and restarts the systemd unit (B0.11 — no containers
# anywhere in this project). Intended to run on the server itself, as
# root or with sudo rights over the timebank-backend service.
#
# Usage (from a workstation):
#   rsync -az --delete --exclude 'target/' --exclude '.git/' --exclude 'data/' \
#     backend/ root@<test-server>:/opt/timebank-backend/
#   ssh root@<test-server> '/opt/timebank-backend/scripts/deploy.sh'
#
# The --exclude 'data/' is load-bearing, not optional: `data/evidence-storage/`
# (LocalFileStorage's runtime-created upload directory, `timebank.file.storage-path`)
# lives only on the server and is never part of this source tree. Without that
# exclude, `--delete` treats it as "extraneous" and wipes every previously
# uploaded evidence file on every single deploy — silently, since the DB rows
# referencing them are untouched and still say READY (issue 060: this had
# already destroyed every evidence file uploaded since 2026-08-07 before
# being caught).
set -euo pipefail
cd "$(dirname "$0")/.."

# Issue 027: TIMEBANK_ALLOWED_ORIGINS used to default to a placeholder when
# absent from /etc/timebank-backend.env, which silently rejects every
# real-browser request with 403 "Request origin is not allowed" — curl-based
# checks never notice, since curl doesn't send an Origin header, so this can
# look completely healthy to every script-based check while being broken for
# actual users. application.yml now requires it with no fallback (fails
# Spring Boot startup instead), and this check catches it even earlier, with
# a clearer message, before spending time on a build.
ENV_FILE="/etc/timebank-backend.env"
if [ ! -f "$ENV_FILE" ] || ! grep -q '^TIMEBANK_ALLOWED_ORIGINS=.\+' "$ENV_FILE"; then
  echo "deploy.sh: TIMEBANK_ALLOWED_ORIGINS is not set in $ENV_FILE — refusing to deploy." >&2
  echo "deploy.sh: set it to this server's real origin first (see the untracked test-server-credits.md), e.g.:" >&2
  echo "deploy.sh:   echo 'TIMEBANK_ALLOWED_ORIGINS=https://<real-domain>' >> $ENV_FILE" >&2
  exit 1
fi

# 10-StudentIdentityAndOnboarding.md §2.9: the credential slip prints this URL
# as the address a student is told to check before typing their activation
# code, and builds the slip's QR deep link from it. A wrong or missing value
# is worse than a broken request — it puts a domain the school does not own
# onto paper handed to children. Same fail-fast treatment as the origins above.
if ! grep -q '^TIMEBANK_SITE_ROOT_URL=.\+' "$ENV_FILE"; then
  echo "deploy.sh: TIMEBANK_SITE_ROOT_URL is not set in $ENV_FILE — refusing to deploy." >&2
  echo "deploy.sh: set it to this server's real public root URL, e.g.:" >&2
  echo "deploy.sh:   echo 'TIMEBANK_SITE_ROOT_URL=https://<real-domain>' >> $ENV_FILE" >&2
  exit 1
fi

mvn -q -DskipTests package
chown -R timebank:timebank /opt/timebank-backend
systemctl restart timebank-backend
sleep 5
systemctl is-active --quiet timebank-backend && echo "deploy.sh: service is active" \
  || { echo "deploy.sh: service failed to start" >&2; journalctl -u timebank-backend --no-pager -n 40; exit 1; }
