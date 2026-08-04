# TimeBank frontend

React + Vite web app (Student Portal + Admin/Teacher Portal). See `Docs/frontend/Design.md` for the page/flow spec and `Docs/integration/APIContract.md` for the backend contract.

## Local development (default: fully mocked)

```bash
pnpm install
pnpm dev
```

No `.env.local` needed. Every `api.*` call reads/writes the in-memory fixtures in `src/app/mock/data.ts` — this is the default and normal way to work on the frontend.

## Pointing at the remote test server (I0.7/I0.12)

The backend has no CORS configuration (it doesn't need one — production serves the frontend from the same origin), and its session cookie is `SameSite=Lax`, so a plain cross-origin `fetch()` from `localhost` would silently drop the cookie. The fix is a Vite dev-server proxy that makes `/api/*` requests same-origin from the browser's point of view.

1. Copy the example env file and fill in the test server host (see the untracked `test-server-credits.md` at the repo root for where that host is documented — do not commit it here):
   ```bash
   cp .env.example .env.local
   # .env.local:
   # VITE_DEV_PROXY_TARGET=https://<test-server-host>
   # VITE_API_REAL_DOMAINS=auth
   ```
2. `pnpm dev` as usual. `/api/*` requests now proxy to the test server; every other domain still reads mocks (`VITE_API_REAL_DOMAINS` is additive, not all-or-nothing — see `src/app/api/config.ts`).
3. As of I0, only `api.auth.me()` (`GET /me`) has a real branch — no screen calls it yet, so the SPA's own UI won't visibly change. To prove the transport, envelope, and cookie/CSRF handling actually work end to end, hit the proxy directly with `curl` (it forwards for any client on port 5173, not just the SPA):
   ```bash
   # First POST has no CSRF cookie yet, so it 403s — but that response is
   # what actually issues the XSRF-TOKEN cookie (Spring's CSRF filter only
   # generates/saves it while validating a state-changing request, not on
   # a plain GET). This "prime, then retry" step is not optional.
   curl -c cookies.txt -b cookies.txt -X POST http://localhost:5173/api/v1/auth/login \
     -H 'Content-Type: application/json' -d '{"username": "...", "password": "..."}' >/dev/null

   CSRF=$(awk '$6=="XSRF-TOKEN"{print $7}' cookies.txt)
   curl -c cookies.txt -b cookies.txt -X POST http://localhost:5173/api/v1/auth/login \
     -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $CSRF" \
     -d '{"username": "...", "password": "..."}'

   curl -b cookies.txt http://localhost:5173/api/v1/me
   ```
   A `{"data": {...}, "meta": {"requestId": "..."}}` envelope back confirms the proxy, cookies, and CSRF header are all working from a local machine. Wiring an actual screen to a real endpoint is I1/I2's job, not I0's.

   **Why the proxy needs the `configure` hook in `vite.config.ts`, not just `changeOrigin`:** the backend also allow-lists the `Origin` header itself (`RequestOriginFilter`, contract §2.3), separately from CSRF. `changeOrigin: true` only rewrites the outbound `Host` header — a real browser's `fetch()` still sends its own `Origin: http://localhost:5173`, which isn't on the backend's allow-list and gets a `403 FORBIDDEN "Request origin is not allowed"` before CSRF is even checked. `curl` doesn't send an `Origin` header by default, so this gap only shows up when driving the app from an actual browser, not from the curl recipe above. The proxy config rewrites `Origin` to the target for exactly this reason — don't remove it.

## Commands

```bash
pnpm dev       # start Vite dev server
pnpm build     # production build
pnpm preview   # preview a production build
```

There is no lint, test, or typecheck script configured (`tsc --noEmit` is not run separately — Vite's esbuild transform strips types without checking them, so run `npx -y -p typescript tsc --noEmit -p tsconfig.json` manually if you want type errors caught before `pnpm build` will show them).
