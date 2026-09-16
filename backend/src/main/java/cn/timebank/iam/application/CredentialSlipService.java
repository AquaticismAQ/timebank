package cn.timebank.iam.application;

import cn.timebank.iam.application.dto.CredentialSlipDtos.IssueSlipsRequest;
import org.springframework.security.core.Authentication;

/**
 * The credential slip batch (10-StudentIdentityAndOnboarding.md §3.4).
 *
 * <p>Issues one activation code per still-pending student in a class and
 * returns a single PDF to stream. Nothing is stored: not the document, not the
 * codes (only their hashes, by {@code ActivationService}), and not a file
 * record — this is deliberately not the evidence {@code FileStorage} path,
 * which sanitizes and watermarks evidence and would give a credential document
 * a second life behind a short-lived URL.</p>
 */
public interface CredentialSlipService {

    /**
     * @param auth passed through to {@code StudentService} for the roster read
     * @throws cn.timebank.common.error.ApiException 403 for a non-admin, 404 for
     *         an unknown class, 409 when the class has no still-pending member
     *         to issue for
     */
    SlipDocument issueForClass(IssueSlipsRequest request, SlipActor actor, Authentication auth,
                                String resolvedLanguage);

    /**
     * Who asked, resolved by the caller from the real {@code Authentication}
     * and passed in — the same shape {@code SchoolInfoService.update} uses for
     * its {@code sysadmin} check, and for the same reason: it keeps the
     * authorization rule unit-testable instead of reachable only through a real
     * HTTP round trip.
     *
     * @param isAdmin §2.10 (D-9/D-10) makes this a <b>plain role check</b>, not
     *                a data-scope resolver. Teachers get no slip path at all,
     *                not even a scoped one, so there is deliberately no "own
     *                class" branch to get wrong — a data-scope bug on a
     *                reporting endpoint leaks a list, the same bug here mints
     *                credentials.
     * @param displayName printed on every slip's provenance line (§2.8)
     */
    record SlipActor(String userId, String displayName, boolean isAdmin) {
    }

    /**
     * The finished batch. {@code pdf} is handed straight to the response body
     * and then dropped — every slip inside it carries a live credential, so the
     * bytes must not be cached, logged or written anywhere.
     *
     * @param slipCount how many students actually got a slip, which is not the
     *                  class size: §2.7 spares everyone who has already
     *                  activated
     */
    record SlipDocument(byte[] pdf, String filename, int slipCount) {

        @Override
        public String toString() {
            return "SlipDocument[filename=" + filename + ", slipCount=" + slipCount + ", pdf=<redacted>]";
        }
    }
}
