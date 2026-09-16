/**
 * Hands fetched bytes to the browser as a file download.
 *
 * Shared by the CSV import templates and by both credential-slip paths — the
 * class batch (`Docs/optimization/10-StudentIdentityAndOnboarding.md` §3.4)
 * and the admin password reset (§3.7). The slip's bytes are a live credential,
 * so the object URL is revoked immediately rather than left addressable for
 * the life of the page.
 */
export function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}
