package cn.timebank.organization.application.dto;

/**
 * The school's name, and nothing else — the public half of
 * {@link SchoolInfoDto}, for the login page.
 *
 * <p>A separate record rather than reusing {@code SchoolInfoDto} with nulls:
 * that one also carries {@code contact} (a member of staff by name) and
 * {@code studentDevCenter} (a room in a building), neither of which belongs on
 * an unauthenticated endpoint. Data minimisation is a stated commitment here
 * (`08-mlps-level1-security-design-guide.md` §4.3), and a type that cannot hold
 * those fields keeps a future edit from quietly widening what is published.</p>
 */
public record SchoolIdentityDto(String name, String fullName) {
}
