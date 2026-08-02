package cn.timebank.common.i18n;

/**
 * Picks the right per-language column for a resolved language, falling
 * back so a dictionary name is never null or empty on the wire (contract
 * §2.10, B2's exit criteria) — even if a translation is genuinely missing.
 */
public final class LocalizedNames {

    private LocalizedNames() {
    }

    public static String pick(String resolvedLanguage, String nameZh, String nameEn) {
        boolean wantsEnglish = SupportedLanguage.EN.code().equals(resolvedLanguage);
        String primary = wantsEnglish ? nameEn : nameZh;
        String fallback = wantsEnglish ? nameZh : nameEn;
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return "";
    }
}
