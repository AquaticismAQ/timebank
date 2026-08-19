#!/usr/bin/env bash
# Runs the full test suite (unit + ArchUnit + integration against real
# MySQL) exactly as B0.14 requires: on the server, never via Testcontainers
# (this project does not use Docker — Docs/implementation/01-Overview.md §6).
#
# Requires TIMEBANK_DB_HOST/_PORT/_USERNAME/_PASSWORD in the environment for
# an account with CREATE/DROP on `timebank_it_%` (see the untracked
# test-server-credits.md). No secrets are hardcoded in this script, so it
# stays safe to commit; if ~/.timebank.env exists (see
# Docs/operations/DeploymentEnv.md) it's sourced first as a convenience —
# that file is untracked too, never this one.
#
# Usage (on the server, after syncing source):
#   cd /opt/timebank-backend && ./scripts/verify.sh
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -f "$HOME/.timebank.env" ]; then
  # shellcheck disable=SC1090,SC1091
  source "$HOME/.timebank.env"
fi

: "${TIMEBANK_DB_HOST:?TIMEBANK_DB_HOST must be set}"
: "${TIMEBANK_DB_USERNAME:?TIMEBANK_DB_USERNAME must be set}"
: "${TIMEBANK_DB_PASSWORD:?TIMEBANK_DB_PASSWORD must be set}"

mvn -q verify
echo "verify.sh: all tests passed"
