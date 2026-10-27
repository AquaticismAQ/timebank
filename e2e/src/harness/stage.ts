import type { Locator, Page } from 'playwright';
import type { Narrator } from './narrator.js';
import { screenIdentity, type Snapshots } from './snapshots.js';

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
  snapshots?: Snapshots;
}

const BOUND = new WeakMap<Page, Binding>();

export function bindStage(page: Page, binding: Binding): void {
  BOUND.set(page, binding);
}

/**
 * How long to hold still after an action, so a viewer can take it in.
 *
 * Configurable (`--beat <ms>`) because the right value depends on the room and
 * on whether the narration is being spoken over it. Half a second: a full second
 * made the demonstration run long, and the spoken narration already holds each
 * step for as long as it takes to say.
 */
let beatMs = 500;

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
    await page.waitForTimeout(250);
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
  await BOUND.get(page)?.snapshots?.take(page, pageNameZh);
}

export async function tap(locator: Locator, whatZh: string): Promise<void> {
  const page = locator.page();
  say(page, `现在点击${whatZh}`);
  await point(locator);
  // A dialog or an in-page form is gone the moment its button is clicked, so its
  // picture has to be taken first — and kept only if the click really did change
  // the screen (a dialog opened or closed, the view or the page changed).
  const snapshots = BOUND.get(page)?.snapshots;
  const before = snapshots?.enabled ? await screenIdentity(page) : null;
  const held = before !== null ? await snapshots!.grab(page) : null;
  await locator.click();
  await unpoint(page);
  await beat(page);
  if (held) {
    // A submit that waits on the server changes the screen after the beat, not
    // within it; give it a moment before deciding nothing changed.
    for (let i = 0; i < 10; i++) {
      if ((await screenIdentity(page)) !== before) {
        snapshots!.save(held.frame, held.title || whatZh);
        break;
      }
      await page.waitForTimeout(200);
    }
  }
}

/**
 * The control under a form label.
 *
 * <b>Matches the start of the label, not the whole of it</b>, and that is the
 * point: labels grow suffixes. `认定时长` became `认定时长（小时）` when the
 * field was given its unit (issue 132), and an exact match turned that into a
 * thirty-second timeout in the middle of a demonstration. A required marker
 * (`服务日期 *`) is the same shape of problem.
 *
 * There were three copies of this before (issue 140) — `project`, `proposal`
 * and `serviceRecord` steps each had their own, and only the proposal one had
 * been hardened. One copy cannot drift from another.
 */
export function field(page: Page, labelZh: string): Locator {
  return page.locator(
    `xpath=//label[starts-with(normalize-space(.), "${labelZh}")]`
    + `/following-sibling::*[self::input or self::textarea or self::select][1]`,
  );
}

export async function type(locator: Locator, fieldZh: string, value: string): Promise<void> {
  const page = locator.page();
  say(page, `现在填写${fieldZh}`);
  await point(locator);
  try {
    await locator.fill(value);
  } catch (cause) {
    // A renamed label otherwise surfaces as "Timeout 30000ms exceeded waiting
    // for locator xpath=//label[...]", which says nothing about *why* the label
    // is gone. Listing what is actually on screen turns a puzzle into a diff.
    // Scoped to the open dialog when there is one — the labels behind it are
    // not the ones this step was looking at. Flattened, because a label
    // wrapping a <select> reports every option on its own line.
    const dialog = page.locator('div.fixed.inset-0.z-50').last();
    const scope = (await dialog.count()) > 0 ? dialog : page.locator('body');
    const labels = await scope.locator('label').allInnerTexts()
      .then(all => [...new Set(all.map(t => t.replace(/\s+/g, ' ').trim()).filter(Boolean))])
      .catch(() => []);
    throw new Error(
      `Could not fill 「${fieldZh}」 — no form control under a label starting with it.\n`
      + `  labels on screen: ${labels.length ? labels.join(' · ') : '(none)'}\n`
      + `  if one of those is the field, the label was renamed: update the step, not the app.`,
      { cause: cause as Error });
  }
  await unpoint(page);
  await beat(page);
}

export async function choose(
  locator: Locator, fieldZh: string, option: string | { label: string } | { index: number },
): Promise<void> {
  const page = locator.page();
  say(page, `现在选择${fieldZh}`);
  await point(locator);
  await locator.selectOption(option as never);
  await unpoint(page);
  await beat(page);
}
