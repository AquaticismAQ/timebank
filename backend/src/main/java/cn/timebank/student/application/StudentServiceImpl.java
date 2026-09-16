package cn.timebank.student.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.common.OpaqueIds;
import cn.timebank.common.concurrency.ConcurrencyControl;
import cn.timebank.common.error.ApiException;
import cn.timebank.common.pagination.FilterSpecifications;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.security.application.DataScopeResolver;
import cn.timebank.security.application.DataScopeSpecifications;
import cn.timebank.security.application.ScopeDescriptor;
import cn.timebank.security.application.TimeBankPrincipal;
import cn.timebank.student.application.dto.StudentDetailDto;
import cn.timebank.student.application.dto.StudentDirectoryEntryDto;
import cn.timebank.student.application.dto.StudentListItemDto;
import cn.timebank.student.application.dto.StudentRequests.CreateStudentRequest;
import cn.timebank.student.application.dto.StudentRequests.UpdateStudentRequest;
import cn.timebank.student.domain.Grade;
import cn.timebank.student.domain.SchoolClass;
import cn.timebank.student.domain.Student;
import cn.timebank.student.domain.StudentStatus;
import cn.timebank.student.infrastructure.GradeRepository;
import cn.timebank.student.infrastructure.SchoolClassRepository;
import cn.timebank.student.infrastructure.StudentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
class StudentServiceImpl implements StudentService {

    private static final String RESOURCE_TYPE = "students";

    private final StudentRepository studentRepository;
    private final GradeRepository gradeRepository;
    private final SchoolClassRepository classRepository;
    private final AuditService auditService;
    private final DataScopeResolver dataScopeResolver;
    private final StudentScopeFields scopeFields = new StudentScopeFields();

    StudentServiceImpl(StudentRepository studentRepository, GradeRepository gradeRepository,
                        SchoolClassRepository classRepository, AuditService auditService,
                        DataScopeResolver dataScopeResolver) {
        this.studentRepository = studentRepository;
        this.gradeRepository = gradeRepository;
        this.classRepository = classRepository;
        this.auditService = auditService;
        this.dataScopeResolver = dataScopeResolver;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StudentListItemDto> list(Pageable pageable, Authentication auth, String gradeId, String classId,
                                          String status, String search, String resolvedLanguage) {
        Specification<Student> scopeSpec = scopeSpecification(auth);
        Specification<Student> filterSpec = FilterSpecifications.and(
                FilterSpecifications.equalTo("gradeId", gradeId),
                FilterSpecifications.equalTo("classId", classId),
                FilterSpecifications.equalToEnum("status", StudentStatus.class, status),
                FilterSpecifications.searchAnyContains(search, "displayName", "studentNumber"));
        Page<Student> page = studentRepository.findAll(FilterSpecifications.and(scopeSpec, filterSpec), pageable);
        Map<String, Grade> grades = gradeRepository.findAll().stream()
                .collect(Collectors.toMap(Grade::getId, Function.identity()));
        Map<String, SchoolClass> classes = classRepository.findAll().stream()
                .collect(Collectors.toMap(SchoolClass::getId, Function.identity()));
        return page.map(s -> toListItem(s, grades, classes));
    }

    @Override
    @Transactional(readOnly = true)
    public StudentDetailDto getDetail(String id, String resolvedLanguage) {
        Student student = studentRepository.findById(id).orElseThrow(() -> ApiException.notFound("Student not found."));
        return toDetailDto(student, resolvedLanguage);
    }

    @Override
    @Transactional(readOnly = true)
    public StudentDetailDto getDetail(String id, Authentication auth, String resolvedLanguage) {
        Student student = findInScopeOrThrow(idEquals(id), auth);
        return toDetailDto(student, resolvedLanguage);
    }

    private StudentDetailDto toDetailDto(Student student, String resolvedLanguage) {
        Grade grade = gradeRepository.findById(student.getGradeId())
                .orElseThrow(() -> new IllegalStateException("Student references a missing grade"));
        SchoolClass schoolClass = classRepository.findById(student.getClassId())
                .orElseThrow(() -> new IllegalStateException("Student references a missing class"));

        return new StudentDetailDto(
                student.getId(), student.getStudentNumber(), student.getDisplayName(),
                grade.getId(), grade.getSequence(),
                schoolClass.getId(), schoolClass.getName(),
                student.getStatus().name(), student.getGraduationYear(),
                student.getJoinedAt(), student.getVersion(), student.getCreatedAt(), student.getUpdatedAt());
    }

    @Override
    @Transactional
    public StudentDetailDto create(CreateStudentRequest request, String actorId, String resolvedLanguage) {
        if (studentRepository.existsByStudentNumber(request.studentNumber())) {
            throw ApiException.stateConflict("Student number is already in use.");
        }
        requireGrade(request.gradeId());
        requireClass(request.classId());

        Instant now = Instant.now();
        Student student = new Student(OpaqueIds.generate("std"), request.studentNumber(), request.displayName(),
                request.gradeId(), request.classId(), request.enrollmentAcademicYearId(), request.graduationYear(),
                request.joinedAt(), now);
        studentRepository.save(student);

        auditService.record(new AuditEntry(actorId, "students.manage", "student.create", "student", student.getId(),
                null, new StudentSummaryForAudit(student), null));
        return toDetailDto(student, resolvedLanguage);
    }

    @Override
    @Transactional
    public StudentDetailDto update(String id, UpdateStudentRequest request, Authentication auth, String actorId,
                                    String resolvedLanguage) {
        Student student = findInScopeOrThrow(idEquals(id), auth);
        ConcurrencyControl.checkVersion(student.getVersion(), request.version());

        StudentSummaryForAudit before = new StudentSummaryForAudit(student);
        if (request.studentNumber() != null && !request.studentNumber().equals(student.getStudentNumber())) {
            if (studentRepository.existsByStudentNumber(request.studentNumber())) {
                throw ApiException.stateConflict("Student number is already in use.");
            }
            student.setStudentNumber(request.studentNumber());
        }
        if (request.displayName() != null) {
            student.setDisplayName(request.displayName());
        }
        if (request.gradeId() != null) {
            requireGrade(request.gradeId());
            student.setGradeId(request.gradeId());
        }
        if (request.classId() != null) {
            requireClass(request.classId());
            student.setClassId(request.classId());
        }
        student.setUpdatedAt(Instant.now());

        auditService.record(new AuditEntry(actorId, "students.manage", "student.update", "student", student.getId(),
                before, new StudentSummaryForAudit(student), null));
        return toDetailDto(student, resolvedLanguage);
    }

    @Override
    @Transactional
    public void activate(String id, Authentication auth, String actorId) {
        Student student = findInScopeOrThrow(idEquals(id), auth);
        student.setStatus(StudentStatus.ACTIVE);
        student.setUpdatedAt(Instant.now());
        auditService.record(new AuditEntry(actorId, "students.manage", "student.activate", "student", student.getId(),
                null, null, null));
    }

    @Override
    @Transactional
    public void deactivate(String id, Authentication auth, String actorId) {
        Student student = findInScopeOrThrow(idEquals(id), auth);
        student.setStatus(StudentStatus.INACTIVE);
        student.setUpdatedAt(Instant.now());
        auditService.record(new AuditEntry(actorId, "students.manage", "student.deactivate", "student", student.getId(),
                null, null, null));
    }

    @Override
    @Transactional(readOnly = true)
    public List<StudentDirectoryEntryDto> searchDirectory(String search, String resolvedLanguage) {
        if (search == null || search.isBlank()) {
            return List.of();
        }
        Specification<Student> spec = FilterSpecifications.and(
                FilterSpecifications.equalToEnum("status", StudentStatus.class, "ACTIVE"),
                FilterSpecifications.searchAnyContains(search, "displayName", "studentNumber"));
        Page<Student> page = studentRepository.findAll(spec, PageRequest.of(0, 20, Sort.by("displayName")));
        Map<String, Grade> grades = gradeRepository.findAll().stream()
                .collect(Collectors.toMap(Grade::getId, Function.identity()));
        Map<String, SchoolClass> classes = classRepository.findAll().stream()
                .collect(Collectors.toMap(SchoolClass::getId, Function.identity()));
        return page.map(s -> new StudentDirectoryEntryDto(s.getId(), s.getDisplayName(),
                gradeLevelOf(s, grades), classNameOf(s, classes))).getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, String> displayNamesOf(List<String> studentIds) {
        return studentRepository.findAllById(studentIds).stream()
                .collect(Collectors.toMap(Student::getId, Student::getDisplayName));
    }

    private int gradeLevelOf(Student s, Map<String, Grade> grades) {
        Grade grade = grades.get(s.getGradeId());
        return grade != null ? grade.getSequence() : 0;
    }

    private String classNameOf(Student s, Map<String, SchoolClass> classes) {
        SchoolClass schoolClass = classes.get(s.getClassId());
        return schoolClass != null ? schoolClass.getName() : "";
    }

    private Student findInScopeOrThrow(Specification<Student> idSpec, Authentication auth) {
        Specification<Student> scopeSpec = scopeSpecification(auth);
        Specification<Student> combined = scopeSpec == null ? idSpec : idSpec.and(scopeSpec);
        return studentRepository.findOne(combined).orElseThrow(() -> ApiException.notFound("Student not found."));
    }

    private Specification<Student> idEquals(String id) {
        return (root, query, cb) -> cb.equal(root.get("id"), id);
    }

    private Specification<Student> scopeSpecification(Authentication auth) {
        TimeBankPrincipal principal = (CookieSessionUserDetails) auth.getPrincipal();
        boolean grantAll = principal.hasSchoolWideVisibility(); // 07-RoleFinetune.md §2.10
        ScopeDescriptor scope = dataScopeResolver.resolve(principal, RESOURCE_TYPE, grantAll);
        return DataScopeSpecifications.apply(scope, scopeFields, principal.studentId());
    }

    private void requireGrade(String gradeId) {
        if (!gradeRepository.existsById(gradeId)) {
            throw ApiException.invalidRequest("Unknown grade: " + gradeId);
        }
    }

    private void requireClass(String classId) {
        if (!classRepository.existsById(classId)) {
            throw ApiException.invalidRequest("Unknown class: " + classId);
        }
    }

    private StudentListItemDto toListItem(Student s, Map<String, Grade> grades, Map<String, SchoolClass> classes) {
        Grade grade = grades.get(s.getGradeId());
        SchoolClass schoolClass = classes.get(s.getClassId());
        return new StudentListItemDto(
                s.getId(), s.getStudentNumber(), s.getDisplayName(),
                s.getGradeId(), grade != null ? grade.getSequence() : 0,
                s.getClassId(), schoolClass != null ? schoolClass.getName() : "",
                s.getStatus().name(), s.getGraduationYear(), s.getVersion());
    }

    private record StudentSummaryForAudit(String studentNumber, String displayName, String status) {
        StudentSummaryForAudit(Student s) {
            this(s.getStudentNumber(), s.getDisplayName(), s.getStatus().name());
        }
    }
}
