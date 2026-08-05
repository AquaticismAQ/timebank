package cn.timebank.student.application;

import cn.timebank.student.application.dto.StudentDetailDto;
import cn.timebank.student.application.dto.StudentListItemDto;
import cn.timebank.student.application.dto.StudentRequests.CreateStudentRequest;
import cn.timebank.student.application.dto.StudentRequests.UpdateStudentRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** The `student` module's published interface (Design.md §6). */
public interface StudentService {

    /**
     * {@code gradeId}/{@code classId}/{@code status} and {@code search}
     * (matches {@code displayName}/{@code studentNumber}) are contract
     * §2.7's {@code filter[...]}/{@code search} — null/blank means
     * unfiltered, an unrecognized {@code status} matches nothing rather
     * than throwing.
     */
    Page<StudentListItemDto> list(Pageable pageable, String gradeId, String classId, String status,
                                   String search, String resolvedLanguage);

    /**
     * @param includeSensitiveFields whether the caller has permission to see
     *                               email/guardian data — the service, not
     *                               the caller, decides what "sensitive"
     *                               excludes, and audits when it's shown
     */
    StudentDetailDto getDetail(String id, boolean includeSensitiveFields, String actorId, String resolvedLanguage);

    StudentDetailDto create(CreateStudentRequest request, String actorId, String resolvedLanguage);

    StudentDetailDto update(String id, UpdateStudentRequest request, String actorId, String resolvedLanguage);

    void activate(String id, String actorId);

    void deactivate(String id, String actorId);
}
