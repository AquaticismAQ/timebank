import type { Page } from 'playwright';
import type { Account } from '../harness/accounts.js';
import { assertThat } from '../harness/assert.js';

/**
 * Signs in through the real form. Never by setting a cookie or seeding
 * localStorage: the login page is part of what is being demonstrated, and a
 * shortcut here would hide a broken one.
 */
export async function signIn(
  page: Page, baseUrl: string, account: Account, expectPathPrefix: string, theme: 'light' | 'dark' = 'light',
): Promise<void> {
  await page.goto(`${baseUrl}/login`, { waitUntil: 'domcontentloaded' });

  const username = page.getByPlaceholder(/用户名|Username/);
  const password = page.getByPlaceholder(/请输入密码|password/i);
  const submit = page.getByRole('button', { name: /^登录$|^Log In$/ });

  // The school name is fetched, so its arrival means React has mounted and at
  // least one request has resolved. Filling before that is accepted by the DOM
  // and then discarded by the first render of a controlled input — the form goes
  // up with an empty password, the server says the credentials are wrong, and
  // the run stalls 25 seconds later waiting for a redirect that will never come.
  // It only happens without `--slow-mo`, which is to say only in the unattended
  // profile, which is exactly where nobody is watching.
  await page.getByText('学生志愿服务管理平台').waitFor({ state: 'visible', timeout: 25_000 });
  await submit.waitFor({ state: 'visible', timeout: 20_000 });

  for (let attempt = 1; attempt <= 3; attempt++) {
    await username.fill(account.username);
    await password.fill(account.password);
    const ok = await username.inputValue() === account.username
      && (await password.inputValue()).length === account.password.length;
    if (ok) break;
    assertThat(attempt < 3, '登录表单反复丢弃输入，无法继续');
    await page.waitForTimeout(300);
  }

  await submit.click();

  // Fail on the error banner rather than on a timeout. Both mean the login did
  // not work, but only one of them says why.
  const badCredentials = page.getByText('用户名或密码错误，请重试');
  const landed = page.waitForURL(url => url.pathname.startsWith(expectPathPrefix), { timeout: 25_000 });
  const refused = badCredentials.waitFor({ state: 'visible', timeout: 25_000 })
    .then(() => { throw new Error(`登录被拒绝：${account.username}（用户名或密码错误）`); });
  await Promise.race([landed, refused]);

  // Re-apply the presentation settings and reload.
  //
  // Logging in overwrites `timebank-lang` from the account's stored
  // `preferredLanguage`, which is authoritative by design (`App.tsx` I3.4) — so
  // the context's seeded values survive only until the next full navigation
  // happens to re-run the init script. That made the run's language depend on
  // what an account happened to be set to and on the order of the steps after
  // it, which is no way to open a demonstration.
  await page.evaluate(`(() => {
    localStorage.setItem('timebank-lang', 'zh');
    localStorage.setItem('timebank-theme', ${JSON.stringify(theme)});
  })()`);
  await page.reload({ waitUntil: 'domcontentloaded' });
  assertThat(
    page.url().includes(expectPathPrefix),
    `signing in as ${account.username} should land under ${expectPathPrefix}, landed on ${page.url()}`,
  );
}
