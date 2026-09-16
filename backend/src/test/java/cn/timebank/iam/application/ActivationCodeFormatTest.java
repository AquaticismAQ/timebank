package cn.timebank.iam.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 10-StudentIdentityAndOnboarding.md §2.5. The check character exists for one
 * stated purpose — "a child sees 'you mistyped that' rather than a round-trip
 * and a bare invalid code" — so the transposition and substitution tests below
 * are the requirement itself, not incidental coverage.
 */
class ActivationCodeFormatTest {

    /**
     * Fixed vectors, hand-computed, so this suite pins the algorithm to
     * Crockford's rather than merely to whatever {@code ActivationCodeFormat}
     * happens to do. Each is small enough to check by eye: read the nine data
     * characters as a base-32 integer, take it modulo 37, and index the
     * 37-symbol check alphabet {@code 0-9 A-Z(no ILOU) * ~ $ = U}.
     *
     * <p>Without these, every other test here would be self-consistent — a
     * generator and a verifier that agree on a <em>wrong</em> checksum pass
     * them all.</p>
     */
    @ParameterizedTest(name = "{0} -> check ''{1}''")
    @CsvSource({
            // value 1   -> 1 % 37 = 1  -> '1'
            "000000001, 1",
            // value 31  -> 31 % 37 = 31 -> 'Z' (last data symbol)
            "00000000Z, Z",
            // value 32  -> 32 % 37 = 32 -> '*' (first check-only symbol)
            "000000010, *",
            // value 34  -> 34 % 37 = 34 -> '$'
            "000000012, $",
            // value 37  -> 37 % 37 = 0  -> '0' (the wrap)
            "000000015, 0",
            // value 48  -> 48 % 37 = 11 -> 'B'
            "00000001G, B",
            // value 64  -> 64 % 37 = 27 -> 'V'
            "000000020, V",
    })
    void the_check_character_is_crockfords_mod_37_symbol(String data, String expectedCheck) {
        assertThat(ActivationCodeFormat.isWellFormed(data + expectedCheck)).isTrue();

        // And nothing else in the alphabet is accepted in that position.
        for (char other : ActivationCodeFormat.CHECK_ALPHABET.toCharArray()) {
            if (other != expectedCheck.charAt(0)) {
                assertThat(ActivationCodeFormat.isWellFormed(data + other))
                        .as("check '%s' must not verify for data %s", other, data)
                        .isFalse();
            }
        }
    }

    @Test
    void a_generated_code_is_ten_characters_and_verifies() {
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 500; i++) {
            String code = ActivationCodeFormat.generate(random);
            assertThat(code).hasSize(10);
            assertThat(ActivationCodeFormat.isWellFormed(code)).isTrue();
        }
    }

    /**
     * §2.9's typed fallback has a twelve-year-old entering this on a phone
     * keyboard. {@code isWellFormed} still accepts Crockford's five check-only
     * extras (see the vectors above), but nothing printed on a slip carries
     * one.
     */
    @Test
    void a_generated_code_never_uses_the_awkward_check_only_symbols() {
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 2_000; i++) {
            String code = ActivationCodeFormat.generate(random);
            assertThat(ActivationCodeFormat.DATA_ALPHABET.indexOf(code.charAt(9)))
                    .as("check character of %s", code)
                    .isNotNegative();
        }
    }

    /**
     * The requirement §2.5 states outright. Every adjacent swap of two
     * <em>different</em> characters, at every position, in a large sample of
     * real generated codes — not one hand-picked example.
     */
    @Test
    void every_adjacent_transposition_is_rejected() {
        SecureRandom random = new SecureRandom();
        int checked = 0;
        for (int i = 0; i < 300; i++) {
            String code = ActivationCodeFormat.generate(random);
            for (int pos = 0; pos < code.length() - 1; pos++) {
                if (code.charAt(pos) == code.charAt(pos + 1)) {
                    continue; // swapping equal characters is not an error
                }
                char[] swapped = code.toCharArray();
                char tmp = swapped[pos];
                swapped[pos] = swapped[pos + 1];
                swapped[pos + 1] = tmp;
                assertThat(ActivationCodeFormat.isWellFormed(new String(swapped)))
                        .as("transposing positions %d/%d of %s must be caught", pos, pos + 1, code)
                        .isFalse();
                checked++;
            }
        }
        assertThat(checked).as("sample actually produced transpositions to check").isGreaterThan(2_000);
    }

    @Test
    void every_single_character_substitution_is_rejected() {
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 50; i++) {
            String code = ActivationCodeFormat.generate(random);
            for (int pos = 0; pos < 9; pos++) {
                for (char replacement : ActivationCodeFormat.DATA_ALPHABET.toCharArray()) {
                    if (replacement == code.charAt(pos)) {
                        continue;
                    }
                    char[] mutated = code.toCharArray();
                    mutated[pos] = replacement;
                    assertThat(ActivationCodeFormat.isWellFormed(new String(mutated)))
                            .as("substituting position %d of %s with %s must be caught", pos, code, replacement)
                            .isFalse();
                }
            }
        }
    }

    @Test
    void generated_codes_do_not_repeat() {
        SecureRandom random = new SecureRandom();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5_000; i++) {
            seen.add(ActivationCodeFormat.generate(random));
        }
        // A pool of ~3x10^13 makes a collision in 5000 draws essentially
        // impossible; a constant or a badly seeded source shows up instantly.
        assertThat(seen).hasSize(5_000);
    }

    @Test
    void normalize_folds_what_a_human_actually_types() {
        String code = ActivationCodeFormat.generate(new SecureRandom());

        assertThat(ActivationCodeFormat.normalize(ActivationCodeFormat.group(code))).isEqualTo(code);
        assertThat(ActivationCodeFormat.normalize(code.toLowerCase())).isEqualTo(code);
        assertThat(ActivationCodeFormat.normalize("  " + code.substring(0, 5) + " " + code.substring(5) + " "))
                .isEqualTo(code);
    }

    @Test
    void normalize_maps_crockfords_ambiguous_glyphs_back_onto_digits() {
        // I, L and O are not in the data alphabet at all, precisely so that
        // reading them off paper can be undone rather than guessed at.
        assertThat(ActivationCodeFormat.normalize("IiLlOo")).isEqualTo("111100");
    }

    @Test
    void normalize_does_not_truncate_an_over_long_code() {
        String code = ActivationCodeFormat.generate(new SecureRandom());

        String tooLong = code + "1234";
        assertThat(ActivationCodeFormat.normalize(tooLong)).hasSize(14);
        // Which means it is rejected, rather than silently becoming a lookup
        // for the first ten characters — a different code the caller never held.
        assertThat(ActivationCodeFormat.isWellFormed(ActivationCodeFormat.normalize(tooLong))).isFalse();
    }

    @Test
    void a_short_or_empty_code_is_not_well_formed() {
        assertThat(ActivationCodeFormat.isWellFormed("")).isFalse();
        assertThat(ActivationCodeFormat.isWellFormed(null)).isFalse();
        assertThat(ActivationCodeFormat.isWellFormed("000000001")).isFalse();
    }

    @Test
    void u_is_rejected_in_a_data_position_even_though_it_is_legal_as_a_check_symbol() {
        // U is check-only in Crockford's spec; accepting it as data would
        // reintroduce the letter the alphabet drops.
        assertThat(ActivationCodeFormat.isWellFormed("U00000001")).isFalse();
        assertThat(ActivationCodeFormat.DATA_ALPHABET).doesNotContain("U");
        assertThat(ActivationCodeFormat.CHECK_ALPHABET).contains("U");
    }

    @Test
    void the_printed_grouping_matches_the_slip() {
        assertThat(ActivationCodeFormat.group("HKTZ49QP2M")).isEqualTo("HKTZ4-9QP2M");
    }
}
