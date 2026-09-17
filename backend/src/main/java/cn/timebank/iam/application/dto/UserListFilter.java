package cn.timebank.iam.application.dto;

/**
 * The `GET /users` query narrowing (contract §2.7's {@code search} /
 * {@code filter[...]}), issue 094. A null or blank member means "not
 * filtered" — never "match nothing".
 *
 * <p>{@code gradeId}/{@code classId} filter on the <em>linked student's</em>
 * placement, so they inherently exclude every staff account: a teacher has no
 * grade of their own, only a scope (see {@code UserDto}). That is the intended
 * reading of the Users tab's grade/class filters, not an accident of the join.</p>
 */
public record UserListFilter(String search, String roleCode, String status, String gradeId, String classId) {

    public static final UserListFilter NONE = new UserListFilter(null, null, null, null, null);

    public boolean hasSearch() {
        return isPresent(search);
    }

    public boolean hasRoleCode() {
        return isPresent(roleCode);
    }

    public boolean hasStatus() {
        return isPresent(status);
    }

    public boolean hasGradeId() {
        return isPresent(gradeId);
    }

    public boolean hasClassId() {
        return isPresent(classId);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
