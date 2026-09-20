import { mkdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { chromium } from 'playwright';

/**
 * Generates the evidence files a demonstration uploads.
 *
 * Why this exists: the real files in `../test-evidence` are a genuine volunteer
 * log sheet, and it carries a real school's name and crest, large and legible.
 * Nothing about that is committed — the folder sits outside the repo — but a
 * demonstration puts it on a projector in front of an audience, which is exactly
 * the exposure this repo has twice had to rewrite its history to undo.
 *
 * So the demo uploads these instead: the same *shape* of document, built from
 * the established placeholder set (`Docs/README.md` §2.8). Generated rather than
 * checked in, because binary fixtures rot and nobody can review a JPEG in a
 * pull request.
 *
 *   pnpm tsx scripts/make-demo-evidence.ts [outputDir]
 */
const OUT = resolve(process.argv[2] ?? join(new URL('../../..', import.meta.url).pathname, 'demo-evidence'));

const SHEET = `<!doctype html><html lang="zh"><head><meta charset="utf-8"><style>
  body { font: 15px/1.9 -apple-system, "PingFang SC", sans-serif; margin: 0; padding: 48px 56px;
         width: 760px; color: #1a1a1a; background: #fff; }
  .crest { width: 54px; height: 54px; border-radius: 50%; border: 3px solid rgb(200,50,25);
           display: flex; align-items: center; justify-content: center; font-weight: 700;
           color: rgb(200,50,25); letter-spacing: 1px; }
  header { display: flex; align-items: center; gap: 18px; border-bottom: 2px solid #1a1a1a; padding-bottom: 18px; }
  h1 { font-size: 21px; margin: 0; } h2 { font-size: 15px; margin: 2px 0 0; font-weight: 500; color: #555; }
  section { margin-top: 26px; } .row { margin: 9px 0; }
  .line { display: inline-block; border-bottom: 1px solid #555; min-width: 230px; }
  .sig { margin-top: 42px; display: flex; justify-content: space-between; }
</style></head><body>
  <header>
    <div class="crest">EC</div>
    <div><h1>学生志愿服务确认函</h1><h2>Student Volunteer Service Confirmation</h2></div>
  </header>
  <section>
    <div class="row">学生姓名 Student：<span class="line">Alex L.</span></div>
    <div class="row">就读学校 School：<span class="line">Example City HD Middle School</span></div>
    <div class="row">服务日期 Date：<span class="line">2026-09-18</span></div>
    <div class="row">服务时长 Hours：<span class="line">2.0</span></div>
  </section>
  <section>
    <div class="row">服务机构 Organization：<span class="line">Example Community Library</span></div>
    <div class="row">服务内容 Description：<span class="line" style="min-width:420px">整理少儿阅览区书架，引导读者使用借阅机</span></div>
  </section>
  <div class="sig">
    <div>负责人 Supervisor：<span class="line" style="min-width:170px">Teacher Wei Chen</span></div>
    <div>日期 Date：<span class="line" style="min-width:120px">2026-09-18</span></div>
  </div>
</body></html>`;

const browser = await chromium.launch();
// Sized to the document rather than to a page, so the JPEG is the letter and
// not a letter floating in half a screen of white.
const page = await browser.newPage({ viewport: { width: 872, height: 560 } });
mkdirSync(OUT, { recursive: true });

await page.setContent(SHEET, { waitUntil: 'load' });
// A JPEG and a PDF of the same document, plus a long filename: three different
// shapes of upload, which is what makes them worth having as a set.
await page.screenshot({ path: join(OUT, 'service-confirmation.jpg'), type: 'jpeg', quality: 88 });
await page.screenshot({
  path: join(OUT, 'supervisor-confirmation-signed-by-the-community-library-2026-09-18.jpg'),
  type: 'jpeg', quality: 88,
});
await page.pdf({ path: join(OUT, 'service-confirmation.pdf'), format: 'A4', printBackground: true });
await browser.close();

console.log(`wrote 3 files to ${OUT}`);
