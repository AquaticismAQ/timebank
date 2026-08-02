package cn.timebank.security.application;

import java.util.Set;

/**
 * B1.7: what a principal may see for one resource type. A sealed
 * hierarchy rather than a single "scope string" so a query-builder call
 * site is forced (by the compiler, via exhaustive {@code switch}) to
 * handle every case rather than falling through to an accidental "see
 * everything" default.
 *
 * {@link Grades}, {@link Classes}, and {@link Projects} carry opaque
 * identifiers only — nothing here parses them. An empty set in any of
 * these is a valid, ordinary "assigned to nothing yet" state, not an
 * error (Design.md B1.7 note).
 */
public sealed interface ScopeDescriptor {

    record All() implements ScopeDescriptor {
    }

    record Self() implements ScopeDescriptor {
    }

    record Grades(Set<String> gradeIds) implements ScopeDescriptor {
    }

    record Classes(Set<String> classIds) implements ScopeDescriptor {
    }

    record Projects(Set<String> projectIds) implements ScopeDescriptor {
    }
}
