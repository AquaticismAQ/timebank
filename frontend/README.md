# TimeBank frontend

React + Vite web app (Student Portal + Admin/Teacher Portal). See `Docs/frontend/Design.md` for the page/flow spec and `Docs/integration/APIContract.md` for the backend contract.

There is no mock mode. Every `api.*` call in `src/app/api/client.ts` always talks to the real backend — there used to be an in-memory-fixture fallback (`src/app/mock/data.ts` + a per-domain `VITE_API_REAL_DOMAINS` toggle), removed once every domain had a working real branch and the mock fixtures had drifted enough from real backend shapes to actively hide bugs (see `integration-test/STATUS.md` for examples caught this way). This means `pnpm dev` needs a real backend to talk to — see below.

## Local development

```bash
pnpm install
cp .env.example .env.local   # then set VITE_DEV_PROXY_TARGET — see below
pnpm dev
```

`.env.local` (git-ignored) is required, not optional — `vite.config.ts` fails fast at startup if `VITE_DEV_PROXY_TARGET` is unset, rather than letting every request silently 404. Point it at either the shared test server (`https://tb.example.com`, per `CLAUDE.md`'s real-server testing convention — the usual choice) or a backend you're running locally.

The backend has no CORS configuration (it doesn't need one — production serves the frontend from the same origin), and its session cookie is `SameSite=Lax`, so a plain cross-origin `fetch()` from `localhost` would silently drop the cookie. The fix is a Vite dev-server proxy that makes `/api/*` requests same-origin from the browser's point of view — that's what `VITE_DEV_PROXY_TARGET` configures.

**Why the proxy needs the `configure` hook in `vite.config.ts`, not just `changeOrigin`:** the backend also allow-lists the `Origin` header itself (`RequestOriginFilter`, contract §2.3), separately from CSRF. `changeOrigin: true` only rewrites the outbound `Host` header — a real browser's `fetch()` still sends its own `Origin: http://localhost:5173`, which isn't on the backend's allow-list and gets a `403 FORBIDDEN "Request origin is not allowed"` before CSRF is even checked. `curl` doesn't send an `Origin` header by default, so this gap only shows up when driving the app from an actual browser. The proxy config rewrites `Origin` to the target for exactly this reason — don't remove it.

Log in with one of the real accounts in `Docs/TestAccounts.md`. The shared test server also carries a seeded dataset (`integration-test/`) — projects/records/proposals across every workflow status — so screens aren't empty during local development either.

## Commands

```bash
pnpm dev       # start Vite dev server (needs .env.local, see above)
pnpm build     # production build
pnpm preview   # preview a production build
```

There is no lint, test, or typecheck script configured (`tsc --noEmit` is not run separately — Vite's esbuild transform strips types without checking them, so run `npx -y -p typescript tsc --noEmit -p tsconfig.json` manually if you want type errors caught before `pnpm build` will show them).

## Deploying

```bash
frontend/scripts/deploy.sh [user@host]   # defaults to root@tb.example.com
```

Builds locally (type-check, then `pnpm build`) and rsyncs `dist/` to the server's nginx docroot (`/var/www/timebank`), then reloads nginx. The build runs on the workstation, not the server — the server has no Node/pnpm installed and is a shared, resource-constrained box (see `Docs/implementation/00-Status.md`'s B7 findings), so a whole Node toolchain wasn't installed there just for a one-off static build. `.env.production` (committed — nothing in it is a secret, and there's nothing left to configure in it now that there's no mock mode) is loaded automatically by `vite build`; `VITE_DEV_PROXY_TARGET` is irrelevant to a production build since nginx serves the frontend and reverse-proxies `/api/*` to the backend from the same origin, so no dev proxy is involved at runtime.
