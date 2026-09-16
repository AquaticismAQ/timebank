package cn.timebank.iam.application;

import cn.timebank.common.error.ApiException;
import cn.timebank.iam.domain.User;
import cn.timebank.iam.infrastructure.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 10-StudentIdentityAndOnboarding.md §2.1/§3.2. Each test here targets one
 * of the three failure modes §2.1 names, and is written so that removing the
 * behavior makes it fail rather than merely producing a different-but-still-
 * passing value:
 *
 * <ul>
 *   <li>the collision retry is driven by a scripted draw sequence whose
 *       <em>first</em> draw is already taken, so a generator that skipped
 *       the retry would return the taken one;</li>
 *   <li>the leading-zero rule is checked both statistically against the real
 *       {@link java.security.SecureRandom} source and structurally, by
 *       feeding a short draw and requiring a hard failure instead of the
 *       zero-padding that would quietly reintroduce {@code 00482155};</li>
 *   <li>the string-typed storage is asserted by reflection, so "optimising"
 *       the column to an integer does not compile-and-pass.</li>
 * </ul>
 */
class StudentUsernameGeneratorTest {

    /** Scripted draws, so a test can state exactly what the randomness "produced". */
    private static IntSupplier draws(int... values) {
        AtomicInteger cursor = new AtomicInteger();
        return () -> {
            int index = cursor.getAndIncrement();
            if (index >= values.length) {
                throw new AssertionError("The generator asked for draw #" + (index + 1)
                        + " but the test only scripted " + values.length);
            }
            return values[index];
        };
    }

    @Test
    void a_taken_username_is_redrawn_rather_than_returned() {
        UserRepository repository = mock(UserRepository.class);
        // The first draw collides with an account that already exists; the
        // second does not. Order matters: 42424242 is offered first, so a
        // generator with no retry would hand it straight back.
        when(repository.existsByUsername("42424242")).thenReturn(true);
        when(repository.existsByUsername("51515151")).thenReturn(false);

        StudentUsernameGenerator generator =
                new StudentUsernameGenerator(repository, draws(42424242, 51515151));

        assertThat(generator.generate()).isEqualTo("51515151");
        // Not just "the right answer came out": the taken candidate really was
        // checked against the unique index, which is what makes the retry a
        // collision retry rather than a coincidence of the scripted sequence.
        verify(repository).existsByUsername("42424242");
        verify(repository).existsByUsername("51515151");
    }

    @Test
    void several_consecutive_collisions_are_all_redrawn() {
        UserRepository repository = mock(UserRepository.class);
        when(repository.existsByUsername(anyString())).thenReturn(true);
        when(repository.existsByUsername("80808080")).thenReturn(false);

        StudentUsernameGenerator generator = new StudentUsernameGenerator(repository,
                draws(11111111, 22222222, 33333333, 80808080));

        assertThat(generator.generate()).isEqualTo("80808080");
    }

    @Test
    void an_exhausted_pool_fails_loudly_instead_of_spinning_forever() {
        UserRepository repository = mock(UserRepository.class);
        when(repository.existsByUsername(anyString())).thenReturn(true);
        AtomicInteger drawCount = new AtomicInteger();
        IntSupplier counted = () -> {
            drawCount.incrementAndGet();
            return 12345678;
        };

        StudentUsernameGenerator generator = new StudentUsernameGenerator(repository, counted);

        assertThatThrownBy(generator::generate).isInstanceOf(ApiException.class);
        assertThat(drawCount.get()).isEqualTo(StudentUsernameGenerator.MAX_ATTEMPTS);
    }

    @Test
    void the_real_random_source_never_produces_a_leading_zero() {
        IntSupplier source = StudentUsernameGenerator.secureRandomSource();
        // 500k draws: if the range were ever widened downwards (e.g. to
        // nextInt(100_000_000), the "obvious" way to write this), roughly one
        // draw in ten would fall below 10_000_000 and this could not pass.
        for (int i = 0; i < 500_000; i++) {
            int drawn = source.getAsInt();
            assertThat(drawn)
                    .isBetween(StudentUsernameGenerator.LOWEST, StudentUsernameGenerator.HIGHEST);
            assertThat(Integer.toString(drawn)).matches("[1-9][0-9]{7}");
        }
    }

    @Test
    void a_short_draw_is_rejected_rather_than_zero_padded() {
        UserRepository repository = mock(UserRepository.class);
        // 482155 is §2.1's own example, the number that becomes "00482155"
        // the moment anyone pads it — and then silently becomes 482155 again
        // in a spreadsheet. The generator must refuse it, not repair it.
        StudentUsernameGenerator generator = new StudentUsernameGenerator(repository, draws(482155));

        assertThatThrownBy(generator::generate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("482155");
    }

    @Test
    void an_oversized_draw_is_rejected_too() {
        UserRepository repository = mock(UserRepository.class);
        StudentUsernameGenerator generator = new StudentUsernameGenerator(repository, draws(100_000_000));

        assertThatThrownBy(generator::generate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void both_ends_of_the_range_are_representable_and_eight_digits_long() {
        UserRepository repository = mock(UserRepository.class);

        String lowest = new StudentUsernameGenerator(repository,
                draws(StudentUsernameGenerator.LOWEST)).generate();
        String highest = new StudentUsernameGenerator(repository,
                draws(StudentUsernameGenerator.HIGHEST)).generate();

        assertThat(lowest).isEqualTo("10000000").hasSize(8);
        assertThat(highest).isEqualTo("99999999").hasSize(8);
    }

    @Test
    void the_username_is_carried_and_stored_as_a_string() throws Exception {
        UserRepository repository = mock(UserRepository.class);
        Object generated = new StudentUsernameGenerator(repository, draws(20304050)).generate();

        assertThat(generated).isInstanceOf(String.class);
        // §2.1's third failure mode: "stored as a string, so nobody optimises
        // it to an integer column". The entity field is the column's type.
        assertThat(User.class.getDeclaredField("username").getType()).isEqualTo(String.class);
    }

    @Test
    void draws_are_spread_across_the_range_rather_than_handed_out_in_sequence() {
        IntSupplier source = StudentUsernameGenerator.secureRandomSource();
        List<Integer> drawn = java.util.stream.IntStream.range(0, 1_000)
                .map(i -> source.getAsInt()).boxed().toList();

        // A sequential allocator (the rejected option in §2.1) would produce a
        // strictly increasing run of near-identical neighbours; a random one
        // does neither. Both checks are independently violated by a counter.
        assertThat(Set.copyOf(drawn)).hasSizeGreaterThan(990);
        boolean strictlyIncreasing = java.util.stream.IntStream.range(1, drawn.size())
                .allMatch(i -> drawn.get(i) > drawn.get(i - 1));
        assertThat(strictlyIncreasing).isFalse();
    }
}
