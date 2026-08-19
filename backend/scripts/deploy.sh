#!/usr/bin/env bash
# Builds the fat JAR and restarts the systemd unit (B0.11 — no containers
# anywhere in this project). Intended to run on the server itself, as
# root or with sudo rights over the timebank-backend service.
#
# Usage (from a workstation):
#   rsync -az --delete --exclude 'target/' --exclude '.git/' \
#     backend/ root@<test-server>:/opt/timebank-backend/
#   ssh root@<test-server> '/opt/timebank-backend/scripts/deploy.sh'
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

mvn -q -DskipTests package
chown -R timebank:timebank /opt/timebank-backend
systemctl restart timebank-backend
sleep 5
systemctl is-active --quiet timebank-backend && echo "deploy.sh: service is active" \
  || { echo "deploy.sh: service failed to start" >&2; journalctl -u timebank-backend --no-pager -n 40; exit 1; }
