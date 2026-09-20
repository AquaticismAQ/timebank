/**
 * An assertion that says what it expected and what it found.
 *
 * Deliberately not `node:assert`: every failure here ends up on someone's screen
 * or in a screenshot filename, so the message is the product. `AssertionFailure`
 * is distinguished from a crash so the runner can tell "the system is wrong" from
 * "the script is wrong" — they need different reactions.
 */
export class AssertionFailure extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'AssertionFailure';
  }
}

export function assertThat(condition: boolean, what: string): asserts condition {
  if (!condition) throw new AssertionFailure(what);
}

export function assertEquals<T>(actual: T, expected: T, what: string): void {
  if (actual !== expected) {
    throw new AssertionFailure(`${what} — expected ${JSON.stringify(expected)}, found ${JSON.stringify(actual)}`);
  }
}

export function assertContains(haystack: string, needle: string, what: string): void {
  if (!haystack.includes(needle)) {
    throw new AssertionFailure(`${what} — expected to find ${JSON.stringify(needle)}`);
  }
}

/**
 * For the assertion this whole suite exists to make: a number that must have
 * moved, and moved by the right amount. A "greater than" check would pass on a
 * balance that rose for some unrelated reason.
 */
export function assertRoseBy(before: number, after: number, delta: number, what: string): void {
  if (after - before !== delta) {
    throw new AssertionFailure(
      `${what} — expected ${before} + ${delta} = ${before + delta}, found ${after} (moved by ${after - before})`,
    );
  }
}
