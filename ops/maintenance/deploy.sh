#!/usr/bin/env bash
# Deploys/updates TimeBank's maintenance mode: the bilingual static page,
# the nginx gating snippet, and the base systemd timer/service *definitions*
# (18:00/08:00 Asia/Shanghai, before any on/off/schedule customization). See
# ops/maintenance/README.md for what this actually does and how to operate
# it day to day.
#
# Deliberately does NOT enable/start the timers or touch the maintenance
# flag file — that's ops/maintenance/maintctl.sh's job (on/off/schedule
# mode), so re-running this to pick up an index.html or bypass-key change
# never clobbers whichever mode is currently active. Run maintctl.sh once
# after the very first deploy to actually turn maintenance mode on for the
# first time.
#
# Idempotent and safe to re-run — it does NOT itself edit the live nginx
# site config (/etc/nginx/sites-enabled/<the real site file>); that's a
# deliberate one-time manual step (see README.md §"One-time nginx wiring")
# so a script never silently rewrites a Certbot-managed file. This script
# checks whether the required `include` line is present and just tells you
# if it isn't yet.
#
# Usage (from a workstation with SSH key access to the server):
#   export TIMEBANK_MAINTENANCE_BYPASS_KEY=<a long random token>
#   ops/maintenance/deploy.sh [user@host]
# (If ~/.timebank.env exists, both TIMEBANK_DEPLOY_TARGET and
# TIMEBANK_MAINTENANCE_BYPASS_KEY can live there instead — see
# Docs/operations/DeploymentEnv.md.)
set -euo pipefail
cd "$(dirname "$0")"

if [ -f "$HOME/.timebank.env" ]; then
  # shellcheck disable=SC1090,SC1091
  source "$HOME/.timebank.env"
fi

: "${TIMEBANK_MAINTENANCE_BYPASS_KEY:?TIMEBANK_MAINTENANCE_BYPASS_KEY must be set — generate one with: openssl rand -hex 24}"
TARGET="${1:-${TIMEBANK_DEPLOY_TARGET:-root@tb.example.com}}"

echo "deploy.sh: rendering nginx snippet with the real bypass key (never written to disk in the repo)..."
TMP_CONF="$(mktemp)"
trap 'rm -f "$TMP_CONF"' EXIT
sed "s/__BYPASS_KEY__/${TIMEBANK_MAINTENANCE_BYPASS_KEY}/g" nginx-maintenance.conf.inc > "$TMP_CONF"

echo "deploy.sh: syncing the maintenance page to ${TARGET}:/var/www/timebank-maintenance/..."
ssh "${TARGET}" "mkdir -p /var/www/timebank-maintenance /etc/nginx/snippets"
rsync -az index.html "${TARGET}:/var/www/timebank-maintenance/index.html"

echo "deploy.sh: syncing the nginx gating snippet..."
rsync -az "$TMP_CONF" "${TARGET}:/etc/nginx/snippets/timebank-maintenance.conf"

echo "deploy.sh: syncing systemd timer/service units..."
rsync -az \
  timebank-maintenance-on.service timebank-maintenance-on.timer \
  timebank-maintenance-off.service timebank-maintenance-off.timer \
  "${TARGET}:/etc/systemd/system/"

echo "deploy.sh: reloading systemd unit definitions (not enabling/starting — see maintctl.sh)..."
ssh "${TARGET}" "systemctl daemon-reload"

echo "deploy.sh: verifying nginx config..."
ssh "${TARGET}" "nginx -t"

# -R (not -r): sites-enabled/* are symlinks into sites-available/, and
# plain -r doesn't follow symlinks while recursing, so it always reports
# "not wired" even after the include line was actually added (caught live).
if ssh "${TARGET}" "grep -Rq 'snippets/timebank-maintenance.conf' /etc/nginx/sites-enabled/ 2>/dev/null"; then
  echo "deploy.sh: reloading nginx (snippet already wired into the site config)..."
  ssh "${TARGET}" "systemctl reload nginx"
else
  echo "deploy.sh: WARNING — the site config doesn't include the maintenance snippet yet."
  echo "deploy.sh: this is a one-time manual step — see ops/maintenance/README.md, section"
  echo "deploy.sh: \"One-time nginx wiring\". Nginx was NOT reloaded; maintenance mode is inert until you do this."
fi

echo "deploy.sh: done. Run ops/maintenance/maintctl.sh {on|off|schedule|status} to set/check the active mode."
