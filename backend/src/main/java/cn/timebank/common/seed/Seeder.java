package cn.timebank.common.seed;

/**
 * One module's contribution to the production-sized test dataset (B0.9,
 * `Design.md` §3.3 / §20.1's "test data seeder ... built early, not late").
 *
 * No implementations exist yet — {@code student} and {@code servicerecord}
 * don't have entities until B2/B3, and there is nothing honest to generate
 * before then. This interface and {@link SeedRunner} are the harness those
 * phases plug into, so the seeder is infrastructure decided now rather than
 * retrofitted once the dataset is large enough to matter (the trap
 * `Design.md` §3.3 names: sizing anything from the 186-student frontend
 * mock). The full 5,000-student run happens on staging, not this shared
 * test server — see `Docs/implementation/01-Overview.md` §6.
 */
public interface Seeder {

    /** Lower runs first — base data before the records that reference it. */
    int order();

    String describe();

    /** @return the number of rows this seeder created, for the run summary. */
    long seed();
}
