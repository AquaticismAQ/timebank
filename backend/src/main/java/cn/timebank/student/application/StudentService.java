package cn.timebank.student.application;

import cn.timebank.student.application.dto.StudentDetailDto;
import cn.timebank.student.application.dto.StudentListItemDto;
import cn.timebank.student.application.dto.StudentRequests.CreateStudentRequest;
import cn.timebank.student.application.dto.StudentRequests.UpdateStudentRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** The `student` module's published interface (Design.md §6). */
public interface StudentService {

    Page<StudentListItemDto> list(Pageable pageable, String resolvedLanguage);

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
