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

mvn -q -DskipTests package
chown -R timebank:timebank /opt/timebank-backend
systemctl restart timebank-backend
sleep 5
systemctl is-active --quiet timebank-backend && echo "deploy.sh: service is active" \
  || { echo "deploy.sh: service failed to start" >&2; journalctl -u timebank-backend --no-pager -n 40; exit 1; }
