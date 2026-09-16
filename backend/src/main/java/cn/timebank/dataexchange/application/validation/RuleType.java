package cn.timebank.dataexchange.application.validation;

/**
 * The fixed catalogue of rule types (plan §2.2, decision D-2).
 *
 * <p>Deliberately a closed enum rather than an expression language. An
 * evaluator that can run arbitrary logic is a security surface of its own, and
 * this is the one component whose entire job is handling files supplied by
 * someone else. A closed set is also auditable — you can read the YAML and
 * know exactly what can happen — and it yields stable machine-readable codes
 * for the error report.</p>
 *
 * <p>Scope is a property of the type, not of the declaration site:</p>
 * <ul>
 *   <li><b>FIELD</b> — sees one cell's value.</li>
 *   <li><b>ROW</b> — sees every cell of the current row (cross-field checks,
 *       e.g. class-belongs-to-grade).</li>
 *   <li><b>FILE</b> — carries state across rows (in-file uniqueness), or
 *       queries the database once per row (existing-row uniqueness).</li>
 * </ul>
 */
public enum RuleType {

    /** FIELD — value must be non-blank. */
    REQUIRED(Scope.FIELD),
    /** FIELD — value must match {@code pattern}. */
    REGEX(Scope.FIELD),
    /** FIELD — value must be no longer than {@code max} characters. */
    MAX_LENGTH(Scope.FIELD),
    /** FIELD — value must parse as an integer, and be within {@code min}/{@code max} when given. */
    INT_RANGE(Scope.FIELD),
    /** FIELD — value must parse as an ISO {@code yyyy-MM-dd} date. */
    DATE_FORMAT(Scope.FIELD),
    /** FIELD — rejects spreadsheet-formula prefixes, control characters and bidirectional overrides. */
    UNSAFE_CONTENT(Scope.FIELD),
    /** FIELD — value must name an existing row in the dictionary named by {@code lookup}. */
    REFERENCE_EXISTS(Scope.FIELD),

    /** ROW — the dictionary row named by {@code lookup} must agree with the value of {@code matches}. */
    REFERENCE_CONSISTENT(Scope.ROW),

    /** FILE — value must not repeat within this upload. */
    UNIQUE_IN_FILE(Scope.FILE),
    /** FILE — value must not already exist in the database, per {@code lookup}. */
    UNIQUE_IN_DB(Scope.FILE),
    /** FILE — the referenced row must exist in the database and satisfy {@code lookup}'s own condition. */
    MUST_EXIST_IN_DB(Scope.FILE);

    public enum Scope {FIELD, ROW, FILE}

    private final Scope scope;

    RuleType(Scope scope) {
        this.scope = scope;
    }

    public Scope scope() {
        return scope;
    }
}
