#!/usr/bin/env bash
# Runs the full test suite (unit + ArchUnit + integration against real
# MySQL) exactly as B0.14 requires: on the server, never via Testcontainers
# (this project does not use Docker — Docs/implementation/01-Overview.md §6).
#
# Requires TIMEBANK_DB_HOST/_PORT/_USERNAME/_PASSWORD in the environment for
# an account with CREATE/DROP on `timebank_it_%` (see the untracked
# test-server-credits.md — never source secrets from a file this script
# reads directly, so this script can be committed safely).
#
# Usage (on the server, after syncing source):
#   cd /opt/timebank-backend && ./scripts/verify.sh
set -euo pipefail
cd "$(dirname "$0")/.."

: "${TIMEBANK_DB_HOST:?TIMEBANK_DB_HOST must be set}"
: "${TIMEBANK_DB_USERNAME:?TIMEBANK_DB_USERNAME must be set}"
: "${TIMEBANK_DB_PASSWORD:?TIMEBANK_DB_PASSWORD must be set}"

mvn -q verify
echo "verify.sh: all tests passed"
