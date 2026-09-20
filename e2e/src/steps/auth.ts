import type { Page } from 'playwright';
import type { Account } from '../harness/accounts.js';
import { assertThat } from '../harness/assert.js';

/**
 * Signs in through the real form. Never by setting a cookie or seeding
 * localStorage: the login page is part of what is being demonstrated, and a
 * shortcut here would hide a broken one.
 */
export async function signIn(page: Page, baseUrl: string, account: Account, expectPathPrefix: string): Promise<void> {
  await page.goto(`${baseUrl}/login`, { waitUntil: 'domcontentloaded' });
  await page.getByPlaceholder(/用户名|Username/).fill(account.username);
  await page.getByPlaceholder(/请输入密码|password/i).fill(account.password);
  await page.getByRole('button', { name: /^登录$|^Log In$/ }).click();
  await page.waitForURL(url => url.pathname.startsWith(expectPathPrefix), { timeout: 15_000 });
  assertThat(
    page.url().includes(expectPathPrefix),
    `signing in as ${account.username} should land under ${expectPathPrefix}, landed on ${page.url()}`,
  );
}
