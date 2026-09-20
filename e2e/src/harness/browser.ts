import { chromium, type Browser, type BrowserContext, type Page } from 'playwright';
import type { Config } from './config.js';

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
  private current: { role: string; context: BrowserContext; page: Page } | null = null;

  constructor(private readonly config: Config) {}

  async start(): Promise<void> {
    this.browser = await chromium.launch({
      headless: this.config.headless,
      slowMo: this.config.slowMo,
      args: this.config.headless ? [] : ['--start-fullscreen'],
    });
  }

  async open(role: string): Promise<Page> {
    if (!this.browser) throw new Error('BrowserHarness.start() was not called');
    if (this.current?.role === role) return this.current.page;
    if (this.current) await this.current.context.close();
    const context = await this.browser.newContext({
      viewport: this.config.headless ? { width: 1600, height: 1000 } : null,
      locale: 'zh-CN',
    });
    const page = await context.newPage();
    this.current = { role, context, page };
    return page;
  }

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
