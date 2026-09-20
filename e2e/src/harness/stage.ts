import type { Locator, Page } from 'playwright';
import type { Narrator } from './narrator.js';

/**
 * Makes the run watchable.
 *
 * A demonstration is not just a passing test played slowly. Three things an
 * audience needs that a test does not: to be told what is about to happen before
 * it happens, to see *where* on the screen it happens, and to be given a moment
 * to look at the result. None of that is the browser's job, so it is this
 * module's.
 *
 * Every interaction therefore goes: say it → point at it → do it → pause. The
 * pause is deliberate dead time and is skipped entirely when the `pace` gate is
 * off, so the unattended profile loses nothing.
 */
interface Binding {
  narrator: Narrator;
  paced: boolean;
  visible: boolean;
}

const BOUND = new WeakMap<Page, Binding>();

export function bindStage(page: Page, binding: Binding): void {
  BOUND.set(page, binding);
}

/**
 * How long to hold still after an action, so a viewer can take it in.
 *
 * Configurable (`--beat <ms>`) because the right value depends on the room and
 * on whether the narration is being spoken over it. Half a second read as rushed
 * once there was a pointer to follow.
 */
let beatMs = 1000;

export function setBeat(ms: number): void {
  beatMs = ms;
}

/**
 * Draws a pointer and a highlight box over whatever is about to be used.
 *
 * Written as a string because this file is typechecked as Node and has no DOM
 * types. The box is interpolated into the expression rather than passed as an
 * argument: `page.evaluate` given a *string* evaluates it as an expression and
 * ignores any argument, so the first version of this built the function and
 * never called it — and the `catch` below meant nothing said so. It was only
 * found by asking the page whether the elements existed.
 */
const SPOTLIGHT_FN = `(box) => {
  const ID_CURSOR = '__e2e_cursor', ID_RING = '__e2e_ring';
  const make = (id, css) => {
    let el = document.getElementById(id);
    if (!el) {
      el = document.createElement('div');
      el.id = id;
      el.style.cssText = css;
      document.body.appendChild(el);
    }
    return el;
  };
  const ring = make(ID_RING,
    'position:fixed;z-index:2147483646;pointer-events:none;border:3px solid rgb(200,50,25);'
    + 'border-radius:10px;box-shadow:0 0 0 4px rgba(200,50,25,.20);'
    + 'transition:all .28s cubic-bezier(.4,0,.2,1);opacity:0');
  const cursor = make(ID_CURSOR,
    'position:fixed;z-index:2147483647;pointer-events:none;width:22px;height:22px;'
    + 'margin:-11px 0 0 -11px;border-radius:50%;background:rgba(200,50,25,.35);'
    + 'border:2px solid rgb(200,50,25);transition:all .28s cubic-bezier(.4,0,.2,1);opacity:0');
  ring.style.opacity = '1';
  ring.style.left = (box.x - 4) + 'px';
  ring.style.top = (box.y - 4) + 'px';
  ring.style.width = (box.width + 8) + 'px';
  ring.style.height = (box.height + 8) + 'px';
  cursor.style.opacity = '1';
  cursor.style.left = (box.x + box.width / 2) + 'px';
  cursor.style.top = (box.y + box.height / 2) + 'px';
}`;

function spotlightExpression(box: { x: number; y: number; width: number; height: number }): string {
  return `(${SPOTLIGHT_FN})(${JSON.stringify(box)})`;
}

/** Reported once, because decoration that silently stops working looks fine. */
let spotlightWarned = false;

async function point(locator: Locator): Promise<void> {
  const page = locator.page();
  const binding = BOUND.get(page);
  if (!binding?.visible) return;
  try {
    await locator.scrollIntoViewIfNeeded({ timeout: 5_000 });
    const box = await locator.boundingBox();
    if (!box) return;
    await page.evaluate(spotlightExpression(box));
    // Long enough for the pointer's own transition to land before the click, so
    // the audience sees it arrive rather than teleport.
    await page.waitForTimeout(320);
  } catch (error) {
    // Pointing is decoration: the run carries on and does the thing. But a
    // demonstration that has quietly lost its pointer should say so once.
    if (!spotlightWarned) {
      spotlightWarned = true;
      console.log(`    (spotlight unavailable: ${error instanceof Error ? error.message : String(error)})`);
    }
  }
}

async function beat(page: Page): Promise<void> {
  const binding = BOUND.get(page);
  if (binding?.paced) await page.waitForTimeout(beatMs);
}

/**
 * Clears the ring and pointer once the action has happened.
 *
 * The box marks what is *about* to be used. Leaving it up afterwards covers the
 * very thing the pause exists to let people look at — a filled field, a new row,
 * a changed status.
 */
async function unpoint(page: Page): Promise<void> {
  const binding = BOUND.get(page);
  if (!binding?.visible) return;
  await page.evaluate(`(() => {
    for (const id of ['__e2e_cursor', '__e2e_ring']) {
      const el = document.getElementById(id);
      if (el) el.style.opacity = '0';
    }
  })()`).catch(() => {});
}

function say(page: Page, zh: string): void {
  BOUND.get(page)?.narrator.step(zh);
}

/**
 * Narrates something with no element to point at — a file upload, a picker whose
 * target only exists once it is open. Still said, because on screen these look
 * like things happening by themselves.
 */
export function narrate(page: Page, zh: string): void {
  say(page, zh);
}

/** Opens a page, announced by the name a person would call it. */
export async function visit(page: Page, url: string, pageNameZh: string): Promise<void> {
  say(page, `现在打开${pageNameZh}`);
  await page.goto(url, { waitUntil: 'domcontentloaded' });
  await beat(page);
}

export async function tap(locator: Locator, whatZh: string): Promise<void> {
  const page = locator.page();
  say(page, `现在点击${whatZh}`);
  await point(locator);
  await locator.click();
  await unpoint(page);
  await beat(page);
}

export async function type(locator: Locator, fieldZh: string, value: string): Promise<void> {
  const page = locator.page();
  say(page, `现在填写${fieldZh}`);
  await point(locator);
  await locator.fill(value);
  await unpoint(page);
  await beat(page);
}

export async function choose(
  locator: Locator, fieldZh: string, option: string | { label: string },
): Promise<void> {
  const page = locator.page();
  say(page, `现在选择${fieldZh}`);
  await point(locator);
  await locator.selectOption(option as never);
  await unpoint(page);
  await beat(page);
}
