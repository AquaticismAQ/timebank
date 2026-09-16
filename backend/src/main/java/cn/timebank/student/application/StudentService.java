package cn.timebank.student.application;

import cn.timebank.student.application.dto.StudentDetailDto;
import cn.timebank.student.application.dto.StudentDirectoryEntryDto;
import cn.timebank.student.application.dto.StudentListItemDto;
import cn.timebank.student.application.dto.StudentRequests.CreateStudentRequest;
import cn.timebank.student.application.dto.StudentRequests.UpdateStudentRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;

/**
 * The `student` module's published interface (Design.md §6).
 *
 * <p>{@code resolvedLanguage} is threaded through every method below even
 * though grade/class are no longer localized here (issue 010 — {@code
 * StudentDetailDto}/{@code StudentListItemDto}/{@code
 * StudentDirectoryEntryDto} carry a raw {@code gradeLevel} int and a raw,
 * language-invariant {@code className} now). Kept rather than removed: ~20
 * call sites across unrelated modules and test suites pass this parameter,
 * and nothing in those callers' behavior depends on grade/class naming —
 * ripping it out is pure signature churn unrelated to issue 010's actual
 * bug. Reconsider if this interface's next real change also touches every
 * call site anyway.
 */
public interface StudentService {

    /**
     * {@code gradeId}/{@code classId}/{@code status} and {@code search}
     * (matches {@code displayName}/{@code studentNumber}) are contract
     * §2.7's {@code filter[...]}/{@code search} — null/blank means
     * unfiltered, an unrecognized {@code status} matches nothing rather
     * than throwing. {@code auth} narrows the result to the caller's
     * data scope (B1.7) before any of the above filters are applied.
     */
    Page<StudentListItemDto> list(Pageable pageable, Authentication auth, String gradeId, String classId,
                                   String status, String search, String resolvedLanguage);

    /**
     * Unscoped — resolves a student by id regardless of the caller's data
     * scope. For **cross-module, system-level lookups only** (a service
     * record/proposal/case resolving a student's name for a record it
     * already has its own, independently-scoped right to read/write) — not
     * for anything a teacher can reach directly by supplying an arbitrary
     * id. {@link #getDetail(String, Authentication, String)} is the
     * scope-checked entry point for that.
     */
    StudentDetailDto getDetail(String id, String resolvedLanguage);

    /**
     * Scope-checked — {@code auth} resolves the caller's data scope (B1.7)
     * and a student outside it is a 404, matching the existing-resources
     * existence-hiding convention (B1.8). Used by {@code GET /students/{id}},
     * where the caller supplies an arbitrary id directly.
     */
    StudentDetailDto getDetail(String id, Authentication auth, String resolvedLanguage);

    StudentDetailDto create(CreateStudentRequest request, String actorId, String resolvedLanguage);

    /** {@code auth} scope-checks {@code id} the same way {@link #getDetail} does, before applying changes. */
    StudentDetailDto update(String id, UpdateStudentRequest request, Authentication auth, String actorId,
                             String resolvedLanguage);

    void activate(String id, Authentication auth, String actorId);

    void deactivate(String id, Authentication auth, String actorId);

    /**
     * {@code GET /students/directory} — a small, capped, name-matched
     * search over active students, for a co-organizer picker. Blank
     * {@code search} returns empty rather than the whole roster; this is a
     * typeahead, not a browse (contract §4.9).
     */
    List<StudentDirectoryEntryDto> searchDirectory(String search, String resolvedLanguage);

    /**
     * B4/proposal: batch id -> displayName, same shape and same
     * cross-module-without-domain-reach-in reason as {@link
     * cn.timebank.iam.application.UserService#displayNamesOf}. Ids absent
     * from the result are silently omitted — callers use that fact to
     * detect ids that don't correspond to a real student.
     */
    Map<String, String> displayNamesOf(List<String> studentIds);
}
