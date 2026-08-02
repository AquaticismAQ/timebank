package cn.timebank.common.i18n;

import org.springframework.stereotype.Component;

/**
 * B2.5: {@code Accept-Language} header → the principal's {@code
 * preferredLanguage} → the school default (contract §2.10). Pure and
 * servlet-free on purpose — callers extract the header and principal
 * themselves (typically in a controller, via {@code @RequestHeader} and
 * {@code Authentication}) so this class is trivially unit-testable and
 * carries no framework coupling of its own.
 */
@Component
public class LanguageResolver {

    public String resolve(String acceptLanguageHeader, String preferredLanguageOrNull, String schoolDefault) {
        SupportedLanguage fromHeader = parseHeader(acceptLanguageHeader);
        if (fromHeader != null) {
            return fromHeader.code();
        }
        SupportedLanguage fromPreference = SupportedLanguage.fromCodeOrNull(preferredLanguageOrNull);
        if (fromPreference != null) {
            return fromPreference.code();
        }
        SupportedLanguage fromDefault = SupportedLanguage.fromCodeOrNull(schoolDefault);
        return fromDefault != null ? fromDefault.code() : SupportedLanguage.ZH_CN.code();
    }

    private SupportedLanguage parseHeader(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        for (String rawToken : header.split(",")) {
            String token = rawToken.split(";")[0].trim();
            SupportedLanguage exact = SupportedLanguage.fromCodeOrNull(token);
            if (exact != null) {
                return exact;
            }
            String lower = token.toLowerCase();
            if (lower.startsWith("zh")) {
                return SupportedLanguage.ZH_CN;
            }
            if (lower.startsWith("en")) {
                return SupportedLanguage.EN;
            }
        }
        return null;
    }
}
