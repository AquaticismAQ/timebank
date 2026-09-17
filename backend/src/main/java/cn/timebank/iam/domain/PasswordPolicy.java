package cn.timebank.iam.domain;

import cn.timebank.common.error.ApiException;
import cn.timebank.common.error.FieldErrorDetail;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The one password rule, applied everywhere a password is set
 * (Design/*&#47;08-mlps-level1-security-design-guide.md §8.1).
 *
 * <p>There are exactly three places a password comes into existence — a student
 * activating an account, an administrator creating a staff account, and a user
 * changing their own — and all three call {@link #validate}. It lives in the
 * domain rather than in bean validation annotations because annotations can be
 * satisfied and then bypassed: a service reached by any other route would skip
 * them silently. The {@code @Size} annotations on the request records remain as
 * a cheap early rejection, not as the control.</p>
 *
 * <p><b>Why these rules.</b> Twelve characters is the floor. The "three of four
 * character classes" test is what an MLPS reviewer checking 强制密码策略 expects
 * to find, and it is deliberately three rather than all four: requiring a symbol
 * of every twelve-year-old produces {@code Password1!} and passwords written on
 * the inside of a pencil case, which would undo the whole point of the credential
 * slip. The blocklist is what actually stops the weak passwords, because
 * composition rules alone happily accept {@code Password1234}.</p>
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;
    /** Of lowercase / uppercase / digit / symbol, how many must appear. */
    public static final int MIN_CHARACTER_CLASSES = 3;

    /**
     * Passwords refused outright, lowercased. Not a large corpus — a big list
     * belongs in a file if this ever needs one — but it covers what people
     * actually reach for first, plus the product's own name, which is the single
     * most likely choice for someone told to pick a password for this system.
     */
    private static final Set<String> BLOCKED = Set.of(
            "password", "password1", "password12", "password123", "password1234",
            "passw0rd", "p@ssw0rd", "qwertyuiop", "qwerty123456", "1qaz2wsx3edc",
            "administrator", "adminadmin", "letmein12345", "welcome12345",
            "iloveyou1234", "abc123456789", "timebank", "timebank123",
            "timebank2026", "shijianyinhang", "123456789012", "1234567890");

    private PasswordPolicy() {
    }

    /**
     * @param username the account the password is for, so a password that merely
     *                 restates it is refused; may be {@code null} where the
     *                 caller genuinely has no username in hand.
     * @throws ApiException {@code VALIDATION_FAILED}, with the failures reported
     *                      against the {@code newPassword} field so the caller can
     *                      show them all at once rather than one per round trip.
     */
    public static void validate(String password, String username) {
        List<String> failures = failuresFor(password, username);
        if (!failures.isEmpty()) {
            throw ApiException.validationFailed("The password does not meet the password policy.",
                    failures.stream()
                            .map(code -> new FieldErrorDetail("newPassword", code, messageFor(code)))
                            .toList());
        }
    }

    /** The rule itself, as codes, so tests and callers can inspect it without catching. */
    public static List<String> failuresFor(String password, String username) {
        if (password == null || password.isBlank()) {
            return List.of("PASSWORD_REQUIRED");
        }
        List<String> failures = new java.util.ArrayList<>();
        if (password.length() < MIN_LENGTH) {
            failures.add("PASSWORD_TOO_SHORT");
        }
        if (password.length() > MAX_LENGTH) {
            failures.add("PASSWORD_TOO_LONG");
        }
        if (characterClasses(password) < MIN_CHARACTER_CLASSES) {
            failures.add("PASSWORD_TOO_SIMPLE");
        }
        if (isBlocked(password)) {
            failures.add("PASSWORD_TOO_COMMON");
        }
        if (containsUsername(password, username)) {
            failures.add("PASSWORD_CONTAINS_USERNAME");
        }
        return List.copyOf(failures);
    }

    /** How many of lowercase / uppercase / digit / symbol the password uses. */
    public static int characterClasses(String password) {
        boolean lower = false, upper = false, digit = false, symbol = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isLowerCase(c)) lower = true;
            else if (Character.isUpperCase(c)) upper = true;
            else if (Character.isDigit(c)) digit = true;
            else symbol = true;
        }
        return (lower ? 1 : 0) + (upper ? 1 : 0) + (digit ? 1 : 0) + (symbol ? 1 : 0);
    }

    private static boolean isBlocked(String password) {
        String lower = password.toLowerCase(Locale.ROOT);
        if (BLOCKED.contains(lower)) {
            return true;
        }
        // A blocked word padded out to reach twelve characters is the obvious way
        // round a list, and is no stronger than the word itself.
        for (String blocked : BLOCKED) {
            if (blocked.length() >= 6 && lower.contains(blocked)) {
                return true;
            }
        }
        return isSingleRepeatedCharacter(lower) || isSequential(lower);
    }

    private static boolean isSingleRepeatedCharacter(String lower) {
        return lower.chars().distinct().count() <= 2;
    }

    /** `123456789012`, `abcdefghijkl` and their reverses, allowing a constant step. */
    private static boolean isSequential(String lower) {
        if (lower.length() < MIN_LENGTH) {
            return false;
        }
        int step = lower.charAt(1) - lower.charAt(0);
        if (step != 1 && step != -1) {
            return false;
        }
        for (int i = 2; i < lower.length(); i++) {
            if (lower.charAt(i) - lower.charAt(i - 1) != step) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsUsername(String password, String username) {
        if (username == null || username.length() < 4) {
            return false;
        }
        return password.toLowerCase(Locale.ROOT).contains(username.toLowerCase(Locale.ROOT));
    }

    private static String messageFor(String code) {
        return switch (code) {
            case "PASSWORD_REQUIRED" -> "A password is required.";
            case "PASSWORD_TOO_SHORT" -> "Use at least " + MIN_LENGTH + " characters.";
            case "PASSWORD_TOO_LONG" -> "Use at most " + MAX_LENGTH + " characters.";
            case "PASSWORD_TOO_SIMPLE" -> "Use at least " + MIN_CHARACTER_CLASSES
                    + " of: lowercase, uppercase, a digit, a symbol.";
            case "PASSWORD_TOO_COMMON" -> "That password is too easy to guess. Choose something else.";
            case "PASSWORD_CONTAINS_USERNAME" -> "The password must not contain your username.";
            default -> "The password does not meet the password policy.";
        };
    }
}
