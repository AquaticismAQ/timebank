import { appendFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';

/**
 * Says what is happening, twice: to the console for whoever is running it, and to
 * `narration/<runId>.txt` as clean prose.
 *
 * The file is the *script* — it is what gets spoken aloud once the wording has
 * been reviewed. So it carries no timestamps, no ids and no English identifiers
 * mid-sentence; those go to the console line only. Keeping the two apart here is
 * what makes the file usable later without editing.
 */
export class Narrator {
  private readonly file: string;

  constructor(narrationDir: string, private readonly runId: string, private readonly quiet = false) {
    mkdirSync(narrationDir, { recursive: true });
    this.file = join(narrationDir, `${runId}.txt`);
  }

  /** A major step, announced. This is the unit TTS will speak, one call at a time. */
  phase(zh: string): void {
    this.write(`\n【${zh}】`, zh);
  }

  /** A detail inside a phase. Spoken too — the pauses are what make it watchable. */
  step(zh: string): void {
    this.write(`  · ${zh}`, zh);
  }

  /** The outcome of a phase, which is the part an audience is waiting for. */
  result(zh: string): void {
    this.write(`  ✓ ${zh}`, zh);
  }

  /** Operator-facing only: never spoken, never in the script file. */
  note(text: string): void {
    if (!this.quiet) console.log(`    ${text}`);
  }

  private write(console_: string, spoken: string): void {
    if (!this.quiet) console.log(`${stamp()} ${console_}`);
    appendFileSync(this.file, `${spoken}\n`, 'utf8');
  }

  get scriptPath(): string {
    return this.file;
  }
}

function stamp(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  return `[${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}]`;
}
