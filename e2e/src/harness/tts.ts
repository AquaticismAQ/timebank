import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { readFromEnvFile } from './config.js';

/**
 * Speaks narration through whichever TTS the operator configured. Nothing about
 * a particular service lives here — the voice is chosen entirely by settings,
 * read from the environment first and `~/.timebank.env` second:
 *
 * - `TTS_URL` — an OpenAI-compatible speech endpoint (`POST …/audio/speech`),
 *   hosted or self-hosted. Needs `TTS_MODEL` and `TTS_VOICE`; `TTS_API_KEY` is
 *   sent as a bearer token when set; `TTS_FORMAT` defaults to `mp3`. The audio is
 *   downloaded, then played locally (`TTS_PLAYER`, else `afplay` / `ffplay` /
 *   `mpg123`, whichever exists).
 * - `TTS_COMMAND` — any local command that speaks its last argument and returns
 *   when it has finished, e.g. `say -v Tingting` on macOS.
 *
 * Blocking on purpose: the speech has to stay level with what is on screen, and
 * a fire-and-forget call would run ahead of the browser and narrate the next
 * step while the audience is still looking at the last one.
 *
 * **Never fatal.** If a line fails, the run says so once and carries on silently.
 * Losing the voice halfway through is recoverable; stopping in front of an
 * audience is not.
 */
export class Speaker {
  private disabled = false;

  /** @param speak returns an error message, or null when the line was spoken. */
  private constructor(private readonly speak: (text: string) => string | null) {}

  /**
   * @returns a speaker, or `null` with the reason — the caller decides whether a
   *   missing voice is worth mentioning, and preflight does mention it.
   */
  static locate(): { speaker: Speaker | null; reason: string } {
    const url = setting('TTS_URL');
    const command = setting('TTS_COMMAND');
    if (url && command) {
      return { speaker: null, reason: 'both TTS_URL and TTS_COMMAND are set — keep one' };
    }
    if (command) {
      return { speaker: new Speaker(text => runSpeaking(command, text)), reason: `TTS: local command (${command.split(/\s+/)[0]})` };
    }
    if (url) {
      const model = setting('TTS_MODEL');
      const voice = setting('TTS_VOICE');
      if (!model || !voice) {
        return { speaker: null, reason: 'TTS_URL is set but TTS_MODEL or TTS_VOICE is not' };
      }
      const player = setting('TTS_PLAYER') ?? defaultPlayer();
      if (!player) {
        return { speaker: null, reason: 'no audio player found — install ffmpeg (ffplay) or mpg123, or set TTS_PLAYER' };
      }
      const service: HttpTts = {
        endpoint: speechEndpoint(url),
        apiKey: setting('TTS_API_KEY'),
        model,
        voice,
        format: setting('TTS_FORMAT') ?? 'mp3',
        player,
      };
      // The host only — never the key, and not the full URL in case it carries one.
      return { speaker: new Speaker(text => speakViaHttp(service, text)), reason: `TTS: ${new URL(service.endpoint).host}, voice ${voice}` };
    }
    return {
      speaker: null,
      reason: 'no TTS configured — set TTS_URL (a speech service) or TTS_COMMAND (a local command); see e2e/README.md',
    };
  }

  say(text: string): void {
    if (this.disabled || !text.trim()) return;
    const error = this.speak(text);
    if (error) {
      this.disabled = true;
      console.log(`    (TTS stopped responding — continuing silently: ${error.slice(0, 160)})`);
    }
  }
}

interface HttpTts {
  endpoint: string;
  apiKey?: string;
  model: string;
  voice: string;
  format: string;
  player: string;
}

function setting(name: string): string | undefined {
  return process.env[name]?.trim() || readFromEnvFile(name) || undefined;
}

/** Accepts a base URL (`https://host/v1`) or the full endpoint. */
function speechEndpoint(url: string): string {
  const trimmed = url.replace(/\/+$/, '');
  return trimmed.endsWith('/audio/speech') ? trimmed : `${trimmed}/audio/speech`;
}

/**
 * Runs `command` with `text` appended as one argument, through `sh` so the
 * command may carry its own flags. The text travels as `$1`, never spliced into
 * the command string, so quotes in a narration line cannot break it.
 */
function runSpeaking(command: string, text: string): string | null {
  const result = spawnSync('/bin/sh', ['-c', `${command} "$1"`, 'tts', text],
    { encoding: 'utf8', timeout: 120_000 });
  if (result.error) return result.error.message;
  return result.status === 0 ? null : (result.stderr?.trim() || `exit ${result.status}`);
}

function speakViaHttp(service: HttpTts, text: string): string | null {
  const dir = mkdtempSync(join(tmpdir(), 'e2e-tts-'));
  try {
    const body = join(dir, 'request.json');
    const audio = join(dir, `speech.${service.format}`);
    writeFileSync(body, JSON.stringify({
      model: service.model, voice: service.voice, input: text, response_format: service.format,
    }));
    // The whole request goes to curl on stdin as a config file, so the API key
    // never appears on a command line where `ps` could show it.
    const config = [
      `url = ${quote(service.endpoint)}`,
      'request = "POST"',
      'header = "Content-Type: application/json"',
      ...(service.apiKey ? [`header = ${quote(`Authorization: Bearer ${service.apiKey}`)}`] : []),
      `data-binary = ${quote(`@${body}`)}`,
      `output = ${quote(audio)}`,
      'fail',
      'silent',
      'show-error',
      'max-time = 60',
    ].join('\n');
    const fetched = spawnSync('curl', ['--config', '-'], { input: config, encoding: 'utf8', timeout: 90_000 });
    if (fetched.error) return `curl: ${fetched.error.message}`;
    if (fetched.status !== 0) return fetched.stderr?.trim() || `curl exit ${fetched.status}`;
    return runSpeaking(service.player, audio);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

function quote(value: string): string {
  return `"${value.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;
}

/** A player that blocks until the file has finished playing. */
function defaultPlayer(): string | undefined {
  const candidates = [
    ...(process.platform === 'darwin' ? ['afplay'] : []),
    'ffplay -nodisp -autoexit -loglevel quiet',
    'mpg123 -q',
  ];
  return candidates.find(c => spawnSync('/bin/sh', ['-c', `command -v ${c.split(' ')[0]}`]).status === 0);
}
