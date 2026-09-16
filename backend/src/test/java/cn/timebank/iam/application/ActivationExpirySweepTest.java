package cn.timebank.iam.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.iam.domain.User;
import cn.timebank.iam.domain.UserStatus;
import cn.timebank.iam.infrastructure.ActivationExpiryRepository;
import cn.timebank.iam.infrastructure.ActivationExpiryRepository.ActivationWindow;
import cn.timebank.iam.infrastructure.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 10-StudentIdentityAndOnboarding.md §3.6 — the sweep that makes the admin
 * list tell the truth about uncollected slips without anyone logging in.
 *
 * <p>Every test here drives the decision the sweep actually makes, not a
 * stubbed answer: the repository supplies candidate rows exactly as the
 * (deliberately non-deciding) SQL would, and the assertions are about which
 * of those candidates come back transitioned. The two skip tests each carry
 * a control account in the same batch, so "correctly skipped" cannot be
 * confused with "the sweep did nothing at all" — without the control, a
 * sweep that returned early would pass both.</p>
 */
class ActivationExpirySweepTest {

    private static final Instant NOW = Instant.parse("2026-09-17T09:00:00Z");
    private static final Instant LAPSED = NOW.minus(Duration.ofDays(2));
    private static final Instant STILL_OPEN = NOW.plus(Duration.ofDays(5));

    private final ActivationExpiryRepository activationExpiryRepository = mock(ActivationExpiryRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final ActivationExpiryTransaction sweep =
            new ActivationExpiryTransaction(activationExpiryRepository, userRepository, auditService);

    /** (a) The account this job exists for: imported, slip never collected, window closed. */
    @Test
    void an_expired_pending_account_is_swept() {
        User uncollected = pending("usr_uncollected", "31415926");
        given(List.of(uncollected), List.of(new ActivationWindow("usr_uncollected", LAPSED, null)));

        ActivationExpiryTransaction.SweepResult result = sweep.expireClosedWindows(NOW, 500);

        assertThat(result.expiredUserIds()).containsExactly("usr_uncollected");
        assertThat(uncollected.getStatus()).isEqualTo(UserStatus.ACTIVATION_EXPIRED);
        assertThat(uncollected.getUpdatedAt()).isEqualTo(NOW);
        assertThat(saved()).containsExactly(uncollected);
    }

    /**
     * (b) §2.7's principle: an account that has already set a password is
     * untouchable however expired its code rows look. Status is held at
     * {@code PENDING_ACTIVATION} for both accounts here on purpose — the
     * password is the only difference between them, so this fails if the
     * stored hash stops being the ground truth, even though a status check
     * would still be in place.
     */
    @Test
    void an_account_that_already_set_a_password_is_not_swept() {
        User activated = pending("usr_activated", "31415926");
        activated.setPasswordHash("$2a$10$chosen.at.activation");
        User control = pending("usr_control", "27182818");
        given(List.of(activated, control),
                List.of(new ActivationWindow("usr_activated", LAPSED, null),
                        new ActivationWindow("usr_control", LAPSED, null)));

        ActivationExpiryTransaction.SweepResult result = sweep.expireClosedWindows(NOW, 500);

        assertThat(activated.getStatus()).isEqualTo(UserStatus.PENDING_ACTIVATION);
        assertThat(result.expiredUserIds()).doesNotContain("usr_activated");
        assertThat(result.alreadyActivated()).isEqualTo(1);
        // The control proves the sweep ran and was working on this batch.
        assertThat(result.expiredUserIds()).containsExactly("usr_control");
        assertThat(control.getStatus()).isEqualTo(UserStatus.ACTIVATION_EXPIRED);
        assertThat(saved()).containsExactly(control);
    }

    /** The same guarantee for an account whose activation also moved its status on. */
    @Test
    void an_account_that_finished_activating_is_not_swept() {
        User activated = pending("usr_activated", "31415926");
        activated.setPasswordHash("$2a$10$chosen.at.activation");
        activated.setStatus(UserStatus.ACTIVE);
        given(List.of(activated), List.of(new ActivationWindow("usr_activated", LAPSED, null)));

        ActivationExpiryTransaction.SweepResult result = sweep.expireClosedWindows(NOW, 500);

        assertThat(activated.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(result.expiredUserIds()).isEmpty();
        verify(userRepository, never()).saveAll(any());
        verifyNoInteractions(auditService);
    }

    /**
     * (c) A window that has not closed. The shape is §2.7's reissue: the lost
     * slip's code lapsed, a replacement was printed, and the replacement is
     * what decides. Sweeping on "has an expired code" alone would expire the
     * student the admin just rescued.
     */
    @Test
    void a_pending_account_whose_window_is_still_open_is_not_swept() {
        User reissued = pending("usr_reissued", "31415926");
        User control = pending("usr_control", "27182818");
        given(List.of(reissued, control),
                List.of(new ActivationWindow("usr_reissued", LAPSED, null),
                        new ActivationWindow("usr_reissued", STILL_OPEN, null),
                        new ActivationWindow("usr_control", LAPSED, null)));

        ActivationExpiryTransaction.SweepResult result = sweep.expireClosedWindows(NOW, 500);

        assertThat(reissued.getStatus()).isEqualTo(UserStatus.PENDING_ACTIVATION);
        assertThat(result.windowStillOpen()).isEqualTo(1);
        // The control proves the sweep ran and was working on this batch.
        assertThat(result.expiredUserIds()).containsExactly("usr_control");
        assertThat(control.getStatus()).isEqualTo(UserStatus.ACTIVATION_EXPIRED);
        assertThat(saved()).containsExactly(control);
    }

    /**
     * A redeemed code is spent (§2.5, single-use), so it cannot hold the
     * window open — only the absence of a password decides whether the
     * account is still pending, and this one has none.
     */
    @Test
    void a_spent_code_does_not_keep_the_window_open() {
        User halfFinished = pending("usr_half", "31415926");
        given(List.of(halfFinished),
                List.of(new ActivationWindow("usr_half", STILL_OPEN, NOW.minus(Duration.ofHours(1)))));

        ActivationExpiryTransaction.SweepResult result = sweep.expireClosedWindows(NOW, 500);

        assertThat(result.expiredUserIds()).containsExactly("usr_half");
    }

    /** An account that never had a slip generated has no window to have closed. */
    @Test
    void an_account_with_no_code_at_all_is_not_swept() {
        User neverIssued = pending("usr_never", "31415926");
        given(List.of(neverIssued), List.of());

        ActivationExpiryTransaction.SweepResult result = sweep.expireClosedWindows(NOW, 500);

        assertThat(neverIssued.getStatus()).isEqualTo(UserStatus.PENDING_ACTIVATION);
        assertThat(result.expiredUserIds()).isEmpty();
    }

    /**
     * The granularity decision, guarded: a school-wide lapse must cost the
     * audit log one row, not one per student (issue 085's lesson).
     */
    @Test
    void the_run_writes_one_audit_row_for_every_account_it_moved() {
        List<User> lapsed = new ArrayList<>();
        List<ActivationWindow> windows = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            lapsed.add(pending("usr_" + i, "3141592" + i));
            windows.add(new ActivationWindow("usr_" + i, LAPSED, null));
        }
        given(lapsed, windows);

        sweep.expireClosedWindows(NOW, 500);

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditService, times(1)).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo("user.activationExpirySweep");
        assertThat(entry.getValue().resourceType()).isEqualTo("user");
        // No actor: no person did this, and inventing one would put a lie in the log.
        assertThat(entry.getValue().actorId()).isNull();
        assertThat(entry.getValue().after())
                .isEqualTo(new ActivationExpiryTransaction.ExpirySweepSummary(40,
                        lapsed.stream().map(User::getId).toList()));
    }

    /** An hourly "expired nothing" row would out-noise the event it records. */
    @Test
    void a_run_that_moves_nothing_writes_no_audit_row() {
        when(activationExpiryRepository.findPendingUserIdsWithAnExpiredCode(any(), anyInt())).thenReturn(List.of());

        ActivationExpiryTransaction.SweepResult result = sweep.expireClosedWindows(NOW, 500);

        assertThat(result.expiredCount()).isZero();
        verifyNoInteractions(auditService);
        verify(userRepository, never()).saveAll(any());
    }

    /** The job's own bound reaches the query, so a backlog cannot become one unbounded transaction. */
    @Test
    void the_batch_limit_is_passed_to_the_query() {
        when(activationExpiryRepository.findPendingUserIdsWithAnExpiredCode(NOW, 25)).thenReturn(List.of());

        sweep.expireClosedWindows(NOW, 25);

        verify(activationExpiryRepository).findPendingUserIdsWithAnExpiredCode(NOW, 25);
    }

    /** Stubs the repository the way the candidate query would answer for these accounts. */
    private void given(List<User> candidates, List<ActivationWindow> windows) {
        List<String> ids = candidates.stream().map(User::getId).toList();
        when(activationExpiryRepository.findPendingUserIdsWithAnExpiredCode(any(), anyInt())).thenReturn(ids);
        when(activationExpiryRepository.findWindowsForUsers(ids)).thenReturn(windows);
        when(userRepository.findAllById(ids)).thenReturn(candidates);
    }

    @SuppressWarnings("unchecked")
    private List<User> saved() {
        ArgumentCaptor<List<User>> captor = ArgumentCaptor.forClass(List.class);
        verify(userRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    private User pending(String id, String username) {
        return User.pendingActivation(id, username, "Example Student", "std_" + id, "zh-CN",
                NOW.minus(Duration.ofDays(9)));
    }
}
