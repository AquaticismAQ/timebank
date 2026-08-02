package cn.timebank.common.i18n;

/** The two languages the frontend ships (contract §2.10). Nothing else is a valid wire value. */
public enum SupportedLanguage {
    ZH_CN("zh-CN"),
    EN("en");

    private final String code;

    SupportedLanguage(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static SupportedLanguage fromCodeOrNull(String code) {
        if (code == null) {
            return null;
        }
        for (SupportedLanguage lang : values()) {
            if (lang.code.equalsIgnoreCase(code.trim())) {
                return lang;
            }
        }
        return null;
    }
}
