import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { join, resolve } from 'node:path';

/**
 * Speaks a line through the local Qwen3 TTS server (`../tts`).
 *
 * Blocking on purpose: the speech has to stay level with what is on screen, and
 * a fire-and-forget call would run ahead of the browser and narrate the next
 * step while the audience is still looking at the last one. A line takes about
 * four seconds, which is also roughly the pacing a demonstration wants.
 *
 * **Never fatal.** If the server is not running, or a line fails, the run says so
 * once and carries on silently. Losing the voice halfway through is recoverable;
 * stopping in front of an audience is not.
 */
export class Speaker {
  private disabled = false;

  private constructor(private readonly ttsDir: string, private readonly python: string) {}

  /**
   * @returns a speaker, or `null` with the reason — the caller decides whether a
   *   missing voice is worth mentioning, and preflight does mention it.
   */
  static locate(repoRoot: string): { speaker: Speaker | null; reason: string } {
    const ttsDir = resolve(process.env.TTS_DIR ?? join(repoRoot, '..', 'tts'));
    if (!existsSync(join(ttsDir, 'qwen_tts_client.py'))) {
      return { speaker: null, reason: `no TTS client at ${ttsDir} (set TTS_DIR)` };
    }
    // The server's venv, because the client imports from it; a bare python3 on
    // this machine does not have the dependencies.
    const venv = join(ttsDir, '.venv', 'bin', 'python');
    const python = existsSync(venv) ? venv : 'python3';

    const status = spawnSync(python, ['qwen_serve.py', 'status'],
      { cwd: ttsDir, encoding: 'utf8', timeout: 20_000 });
    if (!status.stdout?.includes('running')) {
      return {
        speaker: null,
        // Named rather than vague: a cold start is ~6s per line, which would
        // wreck the pacing, so the fix is to start the server, not to wait.
        reason: `TTS server is not running — start it with: ${python} qwen_serve.py start (in ${ttsDir})`,
      };
    }
    return { speaker: new Speaker(ttsDir, python), reason: 'TTS server is running' };
  }

  say(text: string): void {
    if (this.disabled || !text.trim()) return;
    const result = spawnSync(this.python, ['qwen_tts_client.py', text],
      { cwd: this.ttsDir, encoding: 'utf8', timeout: 120_000 });
    if (result.status !== 0) {
      this.disabled = true;
      console.log(`    (TTS stopped responding — continuing silently: ${result.stderr?.trim().slice(0, 160) ?? 'no output'})`);
    }
  }
}
