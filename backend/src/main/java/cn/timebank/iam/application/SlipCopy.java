package cn.timebank.iam.application;

import cn.timebank.common.i18n.LocalizedNames;

/**
 * Every fixed word printed on a credential slip, in the language the request
 * resolved to (10-StudentIdentityAndOnboarding.md §2.9).
 *
 * <h2>Why the strings are here and not in a resource bundle</h2>
 *
 * <p>This backend has no {@code MessageSource} and no {@code messages.properties}.
 * Its i18n is entirely "resolve the request's language, then pick the matching
 * per-row column" — {@code LanguageResolver} plus {@link LocalizedNames#pick},
 * over {@code name_zh}/{@code name_en} pairs in the database. There is no
 * existing mechanism for a <em>literal</em> the server itself authors, because
 * until this document the server never printed one: API error messages are
 * English-only and every other user-facing string is rendered by the frontend
 * from {@code frontend/src/i18n/*.json}.</p>
 *
 * <p>Introducing Spring's {@code MessageSource} for eight labels would be a new
 * i18n mechanism for one caller, and a second place for translations to live
 * that nothing else in the project reads. So the pairs sit here as plain
 * constants and go through the same {@code LocalizedNames.pick} the rest of the
 * API uses, which means they inherit the same defaulting rule: anything that is
 * not {@code "en"} gets Chinese, matching {@code LanguageResolver}'s own
 * fallback.</p>
 *
 * <h2>The wording is copied, not composed</h2>
 *
 * <p>§2.9 requires the paper and the screen to use one vocabulary — "if the
 * paper says 'activation code', the page cannot say 'token' or 'sign-up key',
 * or the student cannot match one to the other". The values below are
 * therefore lifted verbatim from the frontend catalogue rather than written
 * fresh; each is annotated with the key it came from. A slip label with no
 * screen counterpart (the two scanning instructions) is marked as such, and
 * those are the only ones authored here.</p>
 *
 * <p><b>If you edit one of the copied strings, edit its frontend key too.</b>
 * Nothing mechanical enforces that — the backend cannot read the frontend's
 * JSON at build time — so the annotation is the link.</p>
 */
record SlipCopy(String heading, String howToEnter, String scanCaption, String nameLabel,
                String usernameLabel, String codeLabel, String checkAddress,
                String provenanceTemplate, String provenanceMarker, String unnamedIssuer) {

    static SlipCopy forLanguage(String resolvedLanguage) {
        return new SlipCopy(
                // auth.login.firstTimeEntry — the exact phrase §2.9 settles on,
                // and the exact phrase the login page shows above the entry the
                // student is being sent to.
                pick(resolvedLanguage, "首次登录？使用激活码", "First time logging in? Use your activation code"),
                // Slip-only: the screen has no counterpart because the screen is
                // where this sentence lands you.
                pick(resolvedLanguage, "扫描二维码，或访问下方网址。", "Scan the code, or go to the address below."),
                // Slip-only, as above.
                pick(resolvedLanguage, "扫码激活", "Scan to activate"),
                // admin.students.name / student.profile.name
                pick(resolvedLanguage, "姓名", "Name"),
                // auth.login.accountPlaceholder
                pick(resolvedLanguage, "用户名", "Username"),
                // auth.activation.codeLabel
                pick(resolvedLanguage, "激活码", "Activation code"),
                // Slip-only: §2.9's phishing anchor. The screen cannot carry
                // this sentence, because a page telling you to trust its own
                // address bar is exactly what a fake page would also say.
                pick(resolvedLanguage, "请核对地址栏显示的网址是", "Check the address bar says exactly"),
                // §2.8's "issued by, at, valid before". The two languages label
                // the three parts differently rather than word-for-word: Chinese
                // reads better with each part named, English reads better as a
                // sentence, and the English form is left byte-identical to what
                // slips printed before this file existed.
                pick(resolvedLanguage,
                        "签发人：%s  签发时间：%s  有效期至：%s",
                        "Issued by %s at %s  —  valid before %s"),
                pick(resolvedLanguage, "签发人：", "Issued by "),
                // §2.8's provenance line identifies the issuer, and an issuer
                // with no display name at all still has to produce a line that
                // reads as a sentence. Nothing here identifies the student.
                pick(resolvedLanguage, "管理员", "an administrator"));
    }

    String provenance(String issuer, String issuedAt, String expiresAt) {
        return String.format(provenanceTemplate, issuer, issuedAt, expiresAt);
    }

    private static String pick(String resolvedLanguage, String zh, String en) {
        return LocalizedNames.pick(resolvedLanguage, zh, en);
    }
}
