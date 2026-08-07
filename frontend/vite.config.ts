import { defineConfig, loadEnv } from 'vite'
import path from 'path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'

export default defineConfig(({ mode, command }) => {
  // I0.12: point the local dev server at the remote test server as if it
  // were same-origin. This sidesteps two things that would otherwise break
  // a locally-run SPA talking to https://tb.example.com — there is no CORS
  // config on the backend (SecurityConfig.java has none), and the session
  // cookie is `SameSite=Lax` (application.yml), which a true cross-origin
  // fetch would silently drop. See frontend/README.md for the full recipe.
  const env = loadEnv(mode, process.cwd(), '')
  const proxyTarget = env.VITE_DEV_PROXY_TARGET

  // There is no mock mode — every api.* call always hits the real backend
  // (client.ts has no mock fallback left to fall back to). `pnpm dev` with
  // no proxy target configured used to silently work in mock mode; now it
  // would just 404 on every request, which is a confusing way to discover
  // this. Fail loudly at startup instead. Doesn't apply to `pnpm build`
  // (production build never uses this dev-server proxy at all — nginx
  // reverse-proxies /api/* from the same origin at runtime instead).
  if (command === 'serve' && !proxyTarget) {
    throw new Error(
      'VITE_DEV_PROXY_TARGET is required — there is no mock mode. ' +
      'Copy frontend/.env.example to frontend/.env.local and set it to a real backend ' +
      '(e.g. https://tb.example.com). See frontend/README.md.'
    )
  }

  return {
    plugins: [
      // The React and Tailwind plugins are both required for Make, even if
      // Tailwind is not being actively used – do not remove them
      react(),
      tailwindcss(),
    ],
    resolve: {
      alias: {
        // Alias @ to the src directory
        '@': path.resolve(__dirname, './src'),
      },
    },

    // File types to support raw imports. Never add .css, .tsx, or .ts files to this.
    assetsInclude: ['**/*.svg', '**/*.csv'],

    server: proxyTarget ? {
      proxy: {
        '/api': {
          target: proxyTarget,
          changeOrigin: true,
          secure: true,
          cookieDomainRewrite: 'localhost',
          // changeOrigin only rewrites the Host header. The backend also
          // allow-lists the Origin header itself (RequestOriginFilter,
          // contract §2.3) — a real browser always sends one, unlike curl,
          // so it has to be rewritten to the target too or every state-
          // changing request 403s with FORBIDDEN "Request origin is not
          // allowed" before CSRF is even checked.
          configure: (proxy) => {
            proxy.on('proxyReq', (proxyReq) => {
              proxyReq.setHeader('origin', proxyTarget)
            })
          },
        },
      },
    } : undefined,
  }
})
