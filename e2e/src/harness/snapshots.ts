import { createHash } from 'node:crypto';
import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import type { Page } from 'playwright';

/** Every dialog in the app — Radix and hand-rolled alike — renders this overlay. */
const DIALOG = 'div.fixed.inset-0.z-50 >> visible=true';

/**
 * Saves a picture of each screen a flow passes through, for documentation —
 * `--snapshots <dir>` turns it on, and each flow writes into `<dir>/<flow id>/`.
 *
 * Three moments are captured:
 * - every page the flow opens, once its data has loaded;
 * - every dialog at the moment it is submitted or dismissed — the filled form,
 *   the decision being confirmed — which is otherwise gone before any other
 *   moment comes round (`tap` keeps the frame and saves it only if the click
 *   opened or closed a dialog);
 * - the screen each phase ends on, where that step's result is visible.
 *
 * A picture identical to the one before it is skipped, and login pages are never
 * kept: neither tells a reader anything. A flow's folder is emptied when the flow
 * starts, so it always holds one coherent run.
 */
export class Snapshots {
  private dir: string | null = null;
  private count = 0;
  private lastHash = '';

  constructor(private readonly root: string | undefined) {}

  get enabled(): boolean {
    return this.dir !== null;
  }

  startFlow(flowId: string): void {
    if (!this.root) return;
    this.dir = join(this.root, flowId);
    rmSync(this.dir, { recursive: true, force: true });
    mkdirSync(this.dir, { recursive: true });
    this.count = 0;
    this.lastHash = '';
  }

  /** Waits for the page to settle, then saves it. Never fatal. */
  async take(page: Page, label: string): Promise<void> {
    if (!this.dir || isLogin(page)) return;
    // Lists and detail panels fetch after the navigation settles; a picture of a
    // spinner documents nothing.
    await page.waitForLoadState('networkidle', { timeout: 5_000 }).catch(() => {});
    const frame = await page.screenshot({ fullPage: false }).catch(() => null);
    if (frame) this.save(frame, label);
  }

  /** A frame held in memory, for a moment that may or may not turn out worth keeping. */
  async grab(page: Page): Promise<{ frame: Buffer; title: string } | null> {
    if (!this.dir || isLogin(page)) return null;
    const frame = await page.screenshot({ fullPage: false }).catch(() => null);
    if (!frame) return null;
    const title = await page.locator(DIALOG).last().locator('h2, h3').first()
      .innerText({ timeout: 500 }).catch(() => '');
    return { frame, title: title.trim() };
  }

  save(frame: Buffer, label: string): void {
    if (!this.dir) return;
    const hash = createHash('sha256').update(frame).digest('hex');
    if (hash === this.lastHash) return;
    this.lastHash = hash;
    this.count += 1;
    writeFileSync(join(this.dir, `${String(this.count).padStart(2, '0')}-${safe(label)}.png`), frame);
  }
}

/**
 * What a reader would call "the screen": how many dialogs are open, which page,
 * and the heading on it — so an in-page view switch (list → form → list) counts
 * as a change just as a dialog or a navigation does.
 */
export async function screenIdentity(page: Page): Promise<string> {
  const dialogs = await page.locator(DIALOG).count().catch(() => 0);
  const heading = await page.locator('main h2, main h1').first().innerText({ timeout: 300 }).catch(() => '');
  let path = '';
  try { path = new URL(page.url()).pathname; } catch { /* about:blank */ }
  return `${dialogs}|${path}|${heading.trim()}`;
}

function isLogin(page: Page): boolean {
  try {
    return new URL(page.url()).pathname.startsWith('/login');
  } catch {
    return false;
  }
}

function safe(s: string): string {
  return s.replace(/[^\p{L}\p{N}]+/gu, '-').replace(/^-|-$/g, '').slice(0, 40);
}
