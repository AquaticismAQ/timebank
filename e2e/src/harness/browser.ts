import { execFileSync } from 'node:child_process';
import { chromium, type Browser, type BrowserContext, type Page } from 'playwright';
import type { Config } from './config.js';

/**
 * The usable display area, so the window can be sized to it explicitly.
 *
 * `--start-fullscreen` alone does not work on macOS under Playwright — the
 * window comes up at Chromium's own default size and the "full screen" is a
 * claim in the launch arguments rather than anything on screen. Asking the
 * window server for the real bounds and passing them is what actually fills the
 * display.
 *
 * Falls back to a large-but-ordinary size on any platform that will not answer,
 * rather than failing: a demonstration in a small window is worse than ideal,
 * a demonstration that refuses to start is worse than that.
 */
function displayBounds(): { width: number; height: number } {
  if (process.platform === 'darwin') {
    try {
      const out = execFileSync('osascript',
        ['-e', 'tell application "Finder" to get bounds of window of desktop'],
        { encoding: 'utf8', timeout: 5_000 });
      const [, , right, bottom] = out.trim().split(',').map(n => Number(n.trim()));
      if (Number.isFinite(right) && Number.isFinite(bottom) && right > 0 && bottom > 0) {
        return { width: right, height: bottom };
      }
    } catch {
      // fall through
    }
  }
  return { width: 1920, height: 1080 };
}

/**
 * Owns the browser for the whole run.
 *
 * Full screen because this is a demonstration and one nobody can read is not one:
 * `--start-fullscreen` alone still leaves Playwright's default 1280x720 viewport
 * inside a full-screen window, so `viewport: null` is what actually makes the page
 * fill the display.
 *
 * One context per role, discarded on switch. Sharing a context would let a stale
 * admin session make a later student phase pass for the wrong reason, which is
 * exactly the kind of false green this suite exists to avoid.
 */
export class BrowserHarness {
  private browser: Browser | null = null;
  private readonly roles = new Map<string, { context: BrowserContext; page: Page }>();
  private current: { role: string; context: BrowserContext; page: Page } | null = null;

  constructor(private readonly config: Config) {}

  async start(): Promise<void> {
    const screen = displayBounds();
    this.browser = await chromium.launch({
      headless: this.config.headless,
      slowMo: this.config.slowMo,
      // Explicit position and size, because the flags that sound like they do
      // this do not: under Playwright on macOS both `--start-fullscreen` and
      // `--kiosk` are ignored, and the window comes up at Chromium's own default
      // size. Both were tried and measured; neither changed the viewport by a
      // pixel, so neither is left here pretending.
      //
      // This fills the display's width. The remaining ~170px is the tab strip
      // and omnibox, which is browser chrome rather than window size — removing
      // it would need CDP, and the window is big enough to present from.
      args: this.config.headless ? [] : [
        '--window-position=0,0',
        `--window-size=${screen.width},${screen.height}`,
      ],
    });
    if (!this.config.headless) {
      this.screen = screen;
    }
  }

  private screen: { width: number; height: number } | null = null;

  /**
   * The page for a role, signed in once and kept for the whole run.
   *
   * Each role gets its own context, so their cookie jars cannot leak into one
   * another — that isolation is the reason not to share one context, and it is
   * preserved here. What is *not* done any more is closing a context on every
   * switch: that meant re-authenticating at every phase boundary, roughly a
   * dozen logins a run, which the server eventually throttles. The failures then
   * look like wrong credentials at a random phase, and a demonstration that
   * stops halfway with a login error is the worst way to find that out.
   */
  async open(role: string): Promise<Page> {
    if (!this.browser) throw new Error('BrowserHarness.start() was not called');
    const existing = this.roles.get(role);
    if (existing) {
      this.current = { role, ...existing };
      await existing.page.bringToFront();
      return existing.page;
    }
    const context = await this.browser.newContext({
      viewport: this.config.headless ? { width: 1600, height: 1000 } : null,
      locale: 'zh-CN',
    });
    // Seeded per context, before anything loads.
    //
    // **Language.** The narration is Chinese and the selectors match the Chinese
    // labels, so the UI has to be Chinese. Deliberately not the in-app switcher:
    // that calls `PATCH /me/profile` and would permanently change a shared test
    // account's preferred language as a side effect of running a test.
    //
    // **Theme.** The teacher's browser is dark and the student's is light, so an
    // audience can tell at a glance which side of the workflow they are looking
    // at — the two portals are otherwise near-identical in layout.
    //
    // Both are `localStorage` writes because that is what the app reads at
    // startup (`i18n/index.ts`, `App.tsx`'s ThemeProvider), and unlike the
    // language the theme is stored *only* there — nothing is sent to the server.
    //
    // Playwright's own `colorScheme: 'dark'` would do nothing here: this app's
    // `auto` follows the local hour, not `prefers-color-scheme`. Which is also
    // why the student is pinned to `light` rather than left on `auto` — after
    // 19:00 `auto` turns dark and the two roles stop being distinguishable.
    //
    // The argument form, because this file is typechecked as Node: `window` has
    // no type here even though the function runs in the page.
    // The maintenance gate, if this server has one. Set on the context before
    // any navigation: the gate is nginx-level and applies to the SPA and the API
    // alike, so a page that loads without this shows the 503 maintenance page
    // and every later selector fails for a reason that has nothing to do with
    // the selector.
    if (this.config.bypassKey) {
      const host = new URL(this.config.baseUrl).hostname;
      await context.addCookies([{
        name: 'tb_ops_bypass', value: this.config.bypassKey,
        domain: host, path: '/', secure: true, sameSite: 'Lax',
      }]);
    }

    const theme = role === 'admin' ? 'dark' : 'light';
    await context.addInitScript((settings: Array<[string, string]>) => {
      for (const [key, value] of settings) localStorage.setItem(key, value);
    }, [['timebank-lang', 'zh'], ['timebank-theme', theme]] as Array<[string, string]>);
    const page = await context.newPage();
    this.roles.set(role, { context, page });
    this.current = { role, context, page };
    await this.reportWindowSizeOnce(page);
    return page;
  }

  /**
   * Prints the size the window actually got, once, on the first page.
   *
   * Reported rather than assumed: the previous attempt at full screen passed
   * `--start-fullscreen` and nothing else, which macOS ignores under Playwright,
   * so the launch arguments claimed one thing and the screen showed another with
   * nothing anywhere to contradict it.
   */
  private async reportWindowSizeOnce(page: Page): Promise<void> {
    if (this.config.headless || this.reported) return;
    this.reported = true;
    const inner = await page.evaluate<{ w: number; h: number }>(
      '({ w: window.innerWidth, h: window.innerHeight })',
    ).catch(() => null);
    if (!inner) return;
    const fills = this.screen && inner.w >= this.screen.width - 40;
    console.log(`    viewport ${inner.w}x${inner.h}, display ${this.screen?.width}x${this.screen?.height}`
      + (fills ? '' : '  ← not filling the display'));
  }

  private reported = false;

  page(): Page {
    if (!this.current) throw new Error('No page open yet');
    return this.current.page;
  }

  /**
   * Closes only when asked. The end state — the approved record, the changed
   * balance — is the point of a demonstration, and it is also what you most want
   * to look at after a failure.
   */
  async finish(): Promise<void> {
    if (this.config.closeAtEnd) {
      await this.browser?.close();
      return;
    }
    console.log('\nBrowser left open. Press Ctrl-C to close it and exit.');
    await new Promise<void>(resolve => {
      process.once('SIGINT', () => resolve());
      process.once('SIGTERM', () => resolve());
    });
    await this.browser?.close();
  }

  async screenshot(path: string): Promise<void> {
    await this.current?.page.screenshot({ path, fullPage: false }).catch(() => {});
  }
}
