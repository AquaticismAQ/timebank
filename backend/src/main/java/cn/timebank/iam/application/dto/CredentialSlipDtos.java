package cn.timebank.iam.application.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.NotBlank;

/**
 * The request shape for the credential slip document
 * (10-StudentIdentityAndOnboarding.md §3.4, contract §4.15).
 */
public final class CredentialSlipDtos {

    private CredentialSlipDtos() {
    }

    /**
     * §2.8, D-8. <b>A closed enum of exactly two values, not an integer.</b>
     * The point is not that 1000 is rejected — it is that 1000 cannot be
     * <em>expressed</em>. A validated range of 1–100 still admits ninety-odd
     * nonsense values, each of which someone eventually sends and each of
     * which the layout code then has to have an opinion about. Here the type
     * carries the whole rule, so the layout has two cases and no default
     * branch to get wrong.
     *
     * <p>The ceiling is not arbitrary either: it falls out of the minimum
     * scannable QR size alongside a legible name and username at A4.</p>
     *
     * <p>On the wire it is the plain JSON number {@code 1} or {@code 2} — a
     * printing preference an admin picks, not a symbolic constant they should
     * have to learn a spelling for. Anything else fails Jackson's
     * deserialization and surfaces as {@code INVALID_REQUEST} (400) through
     * {@code GlobalExceptionHandler}'s {@code HttpMessageNotReadableException}
     * branch, before any code is issued.</p>
     */
    public enum SlipsPerPage {

        ONE(1),
        TWO(2);

        private final int value;

        SlipsPerPage(int value) {
            this.value = value;
        }

        @JsonValue
        public int value() {
            return value;
        }

        @JsonCreator
        public static SlipsPerPage fromValue(int value) {
            for (SlipsPerPage candidate : values()) {
                if (candidate.value == value) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("slipsPerPage must be 1 or 2");
        }
    }

    /**
     * §3.4's batch: one class, one PDF, one print job.
     *
     * <p>{@code slipsPerPage} is nullable on the wire and defaulted by
     * {@link #resolvedSlipsPerPage()} rather than by a field initialiser,
     * because a record's canonical constructor is what Jackson calls and an
     * omitted JSON property arrives as {@code null} regardless of what the
     * declaration says.</p>
     */
    public record IssueSlipsRequest(@NotBlank String classId, SlipsPerPage slipsPerPage) {

        /** §2.8's default: one slip per page unless the admin asked otherwise. */
        public SlipsPerPage resolvedSlipsPerPage() {
            return slipsPerPage == null ? SlipsPerPage.ONE : slipsPerPage;
        }
    }
}
