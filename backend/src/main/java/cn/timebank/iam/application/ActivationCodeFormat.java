package cn.timebank.iam.application;

import java.security.SecureRandom;
import java.util.function.IntUnaryOperator;

/**
 * The activation code a student reads off a printed slip
 * (10-StudentIdentityAndOnboarding.md §2.5, D-5): nine Crockford base32 data
 * characters plus one check character, printed grouped as {@code HKTZ4-9QP2M}.
 *
 * <p><b>Why nine data characters.</b> §2.5 works the arithmetic: an attacker
 * is not guessing <em>a</em> code, they are guessing against the whole pool of
 * outstanding activations, so 500 pending slips make every guess 500x more
 * likely to land. Nine characters is what keeps the distributed,
 * rate-limit-bypassed case at 0.086% over a 7-day window. Nothing here may be
 * shortened without redoing that table.</p>
 *
 * <p><b>Why Crockford.</b> Its alphabet omits I, L, O and U, so the
 * handwriting and OCR confusions that actually happen on paper
 * ({@code I}/{@code 1}, {@code O}/{@code 0}) are not representable and can be
 * folded back deterministically in {@link #normalize(String)} instead of
 * becoming a wrong code.</p>
 *
 * <p><b>Why Crockford's own mod-37 check symbol</b>, rather than something
 * hand-rolled: it provably catches every error class a child typing off paper
 * produces. Writing the code's data characters as a base-32 integer
 * {@code v}, the check symbol is {@code v mod 37}, and 37 is prime:</p>
 *
 * <ul>
 *   <li><b>Any single wrong character</b> at position {@code i} shifts
 *       {@code v} by {@code (a-b) * 32^i}. For that to vanish mod 37 needs
 *       {@code 37 | (a-b) * 32^i}; 32 is coprime to 37, so it needs
 *       {@code a == b} — i.e. no error at all. Always caught.</li>
 *   <li><b>Any adjacent transposition</b> shifts {@code v} by
 *       {@code (a-b) * 31 * 32^i}. 31 and 32 are both coprime to 37, so again
 *       this only vanishes when {@code a == b}. Always caught — which is the
 *       case §2.5 cares about ("a mistyped or transposed character"), and the
 *       reason a plain sum or a mod-power-of-two check would not do.</li>
 * </ul>
 *
 * <p>This class is the single definition of the algorithm on the server. Its
 * mirror image lives in {@code frontend/src/app/components/auth/ActivationPage.tsx}
 * so the browser can say "you mistyped that" without a round trip; the two must
 * stay in step.</p>
 */
final class ActivationCodeFormat {

    /** Crockford base32. No I, L, O or U — see the class note. */
    static final String DATA_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    /**
     * Crockford's check alphabet: the 32 data symbols plus five that are legal
     * <em>only</em> in the check position, which is what makes it 37 symbols
     * and therefore prime.
     */
    static final String CHECK_ALPHABET = DATA_ALPHABET + "*~$=U";

    static final int DATA_LENGTH = 9;
    static final int CODE_LENGTH = DATA_LENGTH + 1;

    /**
     * Redraw budget for {@link #generate(SecureRandom)}. Acceptance is 32/37
     * per draw, so exhausting this is not something a working random source can
     * do; the bound exists so a broken one fails loudly instead of spinning.
     */
    private static final int MAX_DRAWS = 50;

    private ActivationCodeFormat() {
    }

    /**
     * A fresh code, uniformly drawn from a cryptographically secure source.
     *
     * <p>Draws whose check symbol falls among Crockford's five extras
     * ({@code * ~ $ = U}) are redrawn, so a printed slip only ever carries the
     * 32 data symbols. This is deliberate: the five extras are legal, and
     * {@link #isWellFormed(String)} still accepts them so a code minted by any
     * other conforming generator verifies — but §2.9's typed fallback path has
     * a twelve-year-old entering this on a phone keyboard, where {@code ~} and
     * {@code $} sit behind a modifier layer, and {@code U} re-introduces
     * exactly the letter Crockford dropped. The cost is log2(37/32) ~ 0.21
     * bits out of 45, leaving a pool of ~3.0x10^13 — far too small a dent to
     * move §2.5's table.</p>
     */
    static String generate(SecureRandom random) {
        return generate(bound -> random.nextInt(bound));
    }

    /** Test seam: lets a test force a specific draw sequence. */
    static String generate(IntUnaryOperator source) {
        for (int draw = 0; draw < MAX_DRAWS; draw++) {
            StringBuilder data = new StringBuilder(DATA_LENGTH);
            for (int i = 0; i < DATA_LENGTH; i++) {
                data.append(DATA_ALPHABET.charAt(source.applyAsInt(DATA_ALPHABET.length())));
            }
            char check = checkSymbol(data);
            if (DATA_ALPHABET.indexOf(check) >= 0) {
                return data.append(check).toString();
            }
        }
        throw new IllegalStateException(
                "Activation code source produced " + MAX_DRAWS + " unusable draws in a row");
    }

    /**
     * Folds what a human or a scanner plausibly produces into the one canonical
     * spelling that gets hashed: upper case, separators dropped, and Crockford's
     * ambiguous glyphs mapped back ({@code I}/{@code L} to {@code 1},
     * {@code O} to {@code 0}).
     *
     * <p>Canonicalising <em>before</em> hashing is what makes the printed
     * grouping ({@code HKTZ4-9QP2M}), a lower-case retype, and a stray space all
     * reach the same stored hash. Deliberately does not truncate an over-long
     * input — a 14-character string is a different mistake from a mistyped
     * character, and quietly keeping its first ten would turn it into a lookup
     * for a code the caller never held.</p>
     */
    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (char c : raw.toUpperCase().toCharArray()) {
            char folded = switch (c) {
                case 'I', 'L' -> '1';
                case 'O' -> '0';
                default -> c;
            };
            if (CHECK_ALPHABET.indexOf(folded) >= 0) {
                out.append(folded);
            }
        }
        return out.toString();
    }

    /**
     * Whether {@code normalized} is ten characters of the right alphabets whose
     * check symbol agrees with its data — i.e. whether it is worth a database
     * lookup at all. A code failing this cannot match any stored hash, so the
     * caller may reject it without touching the activation table, and (see
     * {@code ActivationServiceImpl}) without spending the global failure budget.
     */
    static boolean isWellFormed(String normalized) {
        if (normalized == null || normalized.length() != CODE_LENGTH) {
            return false;
        }
        CharSequence data = normalized.subSequence(0, DATA_LENGTH);
        for (int i = 0; i < DATA_LENGTH; i++) {
            if (DATA_ALPHABET.indexOf(data.charAt(i)) < 0) {
                return false;
            }
        }
        char check = normalized.charAt(DATA_LENGTH);
        return CHECK_ALPHABET.indexOf(check) >= 0 && check == checkSymbol(data);
    }

    /** The printed grouping (§2.5): {@code HKTZ49QP2M} to {@code HKTZ4-9QP2M}. */
    static String group(String code) {
        return code.length() == CODE_LENGTH ? code.substring(0, 5) + "-" + code.substring(5) : code;
    }

    /**
     * Crockford's check symbol: the data characters read as a base-32 integer,
     * modulo 37. Nine characters is 45 bits, so a {@code long} holds it exactly
     * — no {@code BigInteger}, and no silent overflow either.
     */
    private static char checkSymbol(CharSequence data) {
        long value = 0;
        for (int i = 0; i < data.length(); i++) {
            int digit = DATA_ALPHABET.indexOf(data.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("Not a Crockford base32 data character");
            }
            value = value * DATA_ALPHABET.length() + digit;
        }
        return CHECK_ALPHABET.charAt((int) (value % CHECK_ALPHABET.length()));
    }
}
