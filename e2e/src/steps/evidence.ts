import { existsSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

/**
 * The evidence files live outside the repo (`../test-evidence`), so nothing is
 * committed and the suite uses the same real files a person would.
 *
 * They are deliberately different from each other: an ordinary JPEG, one with a
 * 75-character filename that exercises long-name rendering, and a small PDF for a
 * second MIME type. A run that files several records takes one each rather than
 * uploading the same picture three times.
 */
export function pickEvidenceFiles(evidenceDir: string, count: number): string[] {
  const all = readdirSync(evidenceDir)
    .filter(name => /\.(jpe?g|png|pdf)$/i.test(name))
    .sort();
  if (all.length === 0) {
    throw new Error(`No jpg/png/pdf files in ${evidenceDir} — evidence is mandatory at submit.`);
  }
  const picked: string[] = [];
  for (let i = 0; i < count; i++) {
    const path = join(evidenceDir, all[i % all.length]);
    if (!existsSync(path)) throw new Error(`Evidence file disappeared: ${path}`);
    picked.push(path);
  }
  return picked;
}
