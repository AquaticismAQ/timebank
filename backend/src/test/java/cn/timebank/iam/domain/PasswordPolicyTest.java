package cn.timebank.iam.domain;

import cn.timebank.common.error.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The password rule, stated as tests so the frontend's copy of it can be checked
 * against something. Every rejection case names <em>which</em> rule rejected it;
 * a test that only asserted "threw" would still pass if the length check quietly
 * became the only rule left.
 */
class PasswordPolicyTest {

    private static final String USERNAME = "48215577";

    @ParameterizedTest
    @ValueSource(strings = {
            "Mars2026Luna!",        // four classes
            "Mars2026LunaGarden",   // three classes, no symbol needed
            "Chosen-By-Student-1",
            "SpringTerm2026Cn",
    })
    void acceptsPasswordsThatMeetEveryRule(String password) {
        assertThatCode(() -> PasswordPolicy.validate(password, USERNAME)).doesNotThrowAnyException();
    }

    @Test
    void rejectsAnythingShorterThanTwelve() {
        assertThat(PasswordPolicy.failuresFor("Ab1!short", USERNAME)).contains("PASSWORD_TOO_SHORT");
        // The boundary itself must be allowed, or "at least 12" is really "at least 13".
        assertThat(PasswordPolicy.failuresFor("Abcdefghij1!", USERNAME)).isEmpty();
    }

    @Test
    void rejectsFewerThanThreeCharacterClasses() {
        // 16 lowercase letters: long, but one class.
        assertThat(PasswordPolicy.failuresFor("abcdefghijklmnop", USERNAME))
                .contains("PASSWORD_TOO_SIMPLE");
        // lower + upper only is still two.
        assertThat(PasswordPolicy.failuresFor("AbcdefghijKlmn", USERNAME))
                .contains("PASSWORD_TOO_SIMPLE");
        // Adding a digit reaches three and is accepted.
        assertThat(PasswordPolicy.failuresFor("AbcdefghijKlm1", USERNAME)).isEmpty();
    }

    @Test
    void rejectsCommonPasswordsEvenWhenTheySatisfyLengthAndClasses() {
        // 12 chars, three classes — and still one of the first things anyone tries.
        assertThat(PasswordPolicy.failuresFor("Password1234", USERNAME))
                .as("composition rules alone accept this, which is why the blocklist exists")
                .contains("PASSWORD_TOO_COMMON");
    }

    @Test
    void rejectsACommonWordMerelyPaddedOutToReachTheLength() {
        assertThat(PasswordPolicy.failuresFor("Xq7-Password-Zz", USERNAME))
                .as("padding a blocked word does not make it stronger")
                .contains("PASSWORD_TOO_COMMON");
    }

    @Test
    void rejectsTheProductsOwnName() {
        assertThat(PasswordPolicy.failuresFor("Timebank2026Aa", USERNAME)).contains("PASSWORD_TOO_COMMON");
    }

    @Test
    void rejectsRepeatedAndSequentialRuns() {
        assertThat(PasswordPolicy.failuresFor("aaaaaaaaaaaaaa", USERNAME)).contains("PASSWORD_TOO_COMMON");
        assertThat(PasswordPolicy.failuresFor("abcdefghijklm", USERNAME)).contains("PASSWORD_TOO_COMMON");
    }

    @Test
    void rejectsAPasswordContainingTheUsername() {
        assertThat(PasswordPolicy.failuresFor("Spring" + USERNAME + "!", USERNAME))
                .as("a student's first instinct is to reuse the number on their slip")
                .contains("PASSWORD_CONTAINS_USERNAME");
        // Case must not be a way around it.
        assertThat(PasswordPolicy.failuresFor("Xy-Admin001-Zz9", "admin001"))
                .contains("PASSWORD_CONTAINS_USERNAME");
    }

    @Test
    void reportsEveryFailureAtOnceRatherThanOnePerAttempt() {
        // Short, one class, and common: a student should see all of it in one go.
        assertThat(PasswordPolicy.failuresFor("aaaa", USERNAME))
                .contains("PASSWORD_TOO_SHORT", "PASSWORD_TOO_SIMPLE", "PASSWORD_TOO_COMMON");
    }

    @Test
    void validateRaisesFieldErrorsTheClientCanRender() {
        assertThatThrownBy(() -> PasswordPolicy.validate("short", USERNAME))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.fieldErrors()).isNotEmpty();
                    assertThat(api.fieldErrors()).allSatisfy(fe ->
                            assertThat(fe.field()).isEqualTo("newPassword"));
                });
    }
}
