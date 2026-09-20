#!/usr/bin/env bash
# Builds the production frontend locally and syncs the static output to the
# shared test server's nginx docroot. Production serves the frontend from
# the same origin as the backend (nginx reverse-proxies /api/*, see
# frontend/README.md) — no dev proxy, no CORS config, needed at runtime.
#
# The build runs on the workstation, not the server: unlike
# backend/scripts/deploy.sh (which must build server-side, since the JAR
# needs the target JVM), the server has no Node/pnpm installed at all, and
# is a shared, resource-constrained box (00-Status.md's B7 findings) —
# installing a whole Node toolchain there just to run a one-off Vite build
# wasn't worth it when the build is fast locally and its static output
# (frontend/dist/) is trivially portable. Only that output is shipped.
#
# Usage (from a workstation with SSH key access to the server):
#   frontend/scripts/deploy.sh [user@host]
# The real host isn't hardcoded here (this repo is public) — pass it as an
# arg, or export TIMEBANK_DEPLOY_TARGET (see the untracked test-server-credits.md).
# If ~/.timebank.env exists (see Docs/operations/DeploymentEnv.md), it's
# sourced first so collaborators don't have to export this by hand.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -f "$HOME/.timebank.env" ]; then
  # shellcheck disable=SC1090,SC1091
  source "$HOME/.timebank.env"
fi

TARGET="${1:-${TIMEBANK_DEPLOY_TARGET:-root@tb.example.com}}"
REMOTE_DOCROOT="/var/www/timebank"

echo "deploy.sh: installing dependencies..."
pnpm install --frozen-lockfile

echo "deploy.sh: type-checking (Vite's esbuild transform strips types without checking them)..."
npx -y -p typescript tsc --noEmit -p tsconfig.json

# Issue 138: the search/filter/sort convention was documented in August and the
# next list page built simply did not know about it. A document cannot catch
# that; this can. Narrow on purpose — see the script's own header.
echo "deploy.sh: checking list-page conventions..."
node scripts/check-list-conventions.mjs

echo "deploy.sh: building production bundle..."
pnpm build

echo "deploy.sh: syncing dist/ to ${TARGET}:${REMOTE_DOCROOT}..."
rsync -az --delete dist/ "${TARGET}:${REMOTE_DOCROOT}/"

echo "deploy.sh: verifying nginx config and reloading..."
ssh "${TARGET}" "nginx -t && systemctl reload nginx"

echo "deploy.sh: done"
