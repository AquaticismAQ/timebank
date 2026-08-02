package cn.timebank.organization.application;

/**
 * The one place every controller resolves "what language for this
 * response" (B2.5) — wraps {@code common.i18n.LanguageResolver} with the
 * school default, so no controller hardcodes a fallback. Lives here (not
 * in {@code common}) because resolving the school default requires reading
 * {@code school_info}, and {@code common} must stay dependency-free
 * (Docs/implementation/00-Status.md §11's module-boundary discipline).
 */
public interface RequestLanguageService {

    String resolve(String acceptLanguageHeader, String preferredLanguageOrNull);
}
