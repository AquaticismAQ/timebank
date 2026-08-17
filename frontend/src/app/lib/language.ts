import i18n from '../../i18n';
import { api } from '../api/client';
import { clearDictionaryCache } from '../api/useDictionaries';

/**
 * I3.4: the three places a user can change language (`LanguageSwitcher`,
 * `ProfilePage`, `AdminProfilePage`) each used to just call
 * `i18n.changeLanguage` + write `localStorage['timebank-lang']` — a
 * same-device-only preference that a student switching devices, or a
 * teacher signing in on a shared computer, would lose every time.
 * `PATCH /me/profile` makes the server the source of truth.
 */
export function changeLanguage(code: 'zh' | 'en'): void {
  i18n.changeLanguage(code);
  localStorage.setItem('timebank-lang', code);
  clearDictionaryCache();
  api.auth.updateProfile({ preferredLanguage: code === 'zh' ? 'zh-CN' : 'en' }).catch(() => {});
}
