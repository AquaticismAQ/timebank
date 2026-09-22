package cn.timebank.student.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.rules.application.RuleResolutionService;
import cn.timebank.rules.application.dto.EffectiveRule;
import cn.timebank.student.infrastructure.StudentProgressQueryRepository;
import java.util.Set;
import cn.timebank.student.infrastructure.AcademicYearRepository;
import cn.timebank.student.domain.AcademicYear;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
class StudentServiceImpl implements StudentService {

    private static final String RESOURCE_TYPE = "students";

    /**
     * Sort keys {@code GET /students} accepts but the database cannot order by —
     * see {@code computedSortPage}. Kept beside the controller's {@code SORTABLE}
     * set: that one decides what may be asked for, this one decides which of
     * those answers cannot come from an {@code order by}.
     */
    private static final Set<String> COMPUTED_SORTS =
            Set.of("gradeLevel", "completedMinutes", "timeCoinBalance");

    private final StudentRepository studentRepository;
    private final GradeRepository gradeRepository;
    private final SchoolClassRepository classRepository;
    private final AcademicYearRepository academicYearRepository;
    private final StudentProgressQueryRepository progressQueryRepository;
    private final RuleResolutionService ruleResolutionService;
    private final AuditService auditService;
    private final DataScopeResolver dataScopeResolver;
    private final StudentScopeFields scopeFields = new StudentScopeFields();

    StudentServiceImpl(StudentRepository studentRepository, GradeRepository gradeRepository,
                        SchoolClassRepository classRepository, AcademicYearRepository academicYearRepository,
                        StudentProgressQueryRepository progressQueryRepository,
                        RuleResolutionService ruleResolutionService, AuditService auditService,
                        DataScopeResolver dataScopeResolver) {
        this.studentRepository = studentRepository;
        this.gradeRepository = gradeRepository;
        this.classRepository = classRepository;
        this.academicYearRepository = academicYearRepository;
        this.progressQueryRepository = progressQueryRepository;
        this.ruleResolutionService = ruleResolutionService;
        this.auditService = auditService;
        this.dataScopeResolver = dataScopeResolver;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StudentListItemDto> list(Pageable pageable, Authentication auth, String gradeId, String classId,
                                          String status, String progressStatus, String search, String resolvedLanguage) {
        Specification<Student> scopeSpec = scopeSpecification(auth);
        Specification<Student> filterSpec = FilterSpecifications.and(
                FilterSpecifications.equalTo("gradeId", gradeId),
                FilterSpecifications.equalTo("classId", classId),
                statusSpecification(status),
                FilterSpecifications.searchAnyContains(search, "displayName", "studentNumber"));

        Map<String, Grade> grades = gradeRepository.findAll().stream()
                .collect(Collectors.toMap(Grade::getId, Function.identity()));
        Map<String, SchoolClass> classes = classRepository.findAll().stream()
                .collect(Collectors.toMap(SchoolClass::getId, Function.identity()));
        ProgressYardstick yardstick = progressYardstick();

        // Resolved into an id set *before* paging, so the page and its total both
        // describe the filtered list (issue 159). Doing it after — which is what
        // the browser used to do — leaves the pager counting rows the filter has
        // already removed, and a selective bucket then empties whole pages that
        // the pager insists exist.
        if (progressStatus != null && !progressStatus.isBlank()) {
            Specification<Student> matching = progressSpecification(
                    scopeSpec, filterSpec, progressStatus, grades, yardstick);
            filterSpec = FilterSpecifications.and(filterSpec, matching);
        }

        Specification<Student> spec = FilterSpecifications.and(scopeSpec, filterSpec);

        String computedSort = computedSortProperty(pageable);
        if (computedSort != null) {
            return computedSortPage(spec, pageable, computedSort, grades, classes, yardstick);
        }

        Page<Student> page = studentRepository.findAll(spec, pageable);
        return page.map(s -> toListItem(s, grades, classes,
                minutesFor(page.getContent(), yardstick), balancesFor(page.getContent()), yardstick));
    }

    /**
     * The sort properties that are computed rather than stored, or null when the
     * request asks for an ordinary column.
     *
     * <p>Only the <b>first</b> order is consulted, because these three cannot be
     * combined with anything: the list is sorted in memory below, and a second
     * order the database applied would be discarded. The wire only ever sends one
     * sort key anyway.</p>
     */
    private static String computedSortProperty(Pageable pageable) {
        return pageable.getSort().stream()
                .map(Sort.Order::getProperty)
                .filter(COMPUTED_SORTS::contains)
                .findFirst()
                .orElse(null);
    }

    /**
     * Sorting by a value that is not a column (issue 159 §4a).
     *
     * <p>Grade <i>level</i>, approved minutes and coin balance are each either
     * derived or owned by another module, so none can appear in an {@code order
     * by}: {@code grade_id} sorts as a string and puts grade 10 before grade 6,
     * and the other two live in {@code service_record} and {@code account}. The
     * page therefore sorted whichever twenty rows the database had chosen, and
     * presented that as an ordering of the roster — "sort by service time" could
     * not surface the one student the on-track filter <i>did</i> show.</p>
     *
     * <p>So the scope and filters resolve to the full matching list first, the
     * three values are read for all of it in two queries, and only then is it
     * ordered and cut to the page. The total comes from that same list, so the
     * pager and the rows still describe one thing. This is the arrangement the
     * progress filter already uses, for the same reason and with the same
     * under-two-hundred-student justification; the alternative is three more
     * aggregate columns maintained on {@code student}, each able to disagree with
     * the ledger it summarises.</p>
     */
    private Page<StudentListItemDto> computedSortPage(
            Specification<Student> spec, Pageable pageable, String property,
            Map<String, Grade> grades, Map<String, SchoolClass> classes, ProgressYardstick yardstick) {
        List<Student> all = studentRepository.findAll(spec);
        Map<String, Long> minutes = minutesFor(all, yardstick);
        Map<String, Long> balances = balancesFor(all);

        List<StudentListItemDto> rows = all.stream()
                .map(s -> toListItem(s, grades, classes, minutes, balances, yardstick))
                .sorted(comparatorFor(property, pageable.getSort().getOrderFor(property)))
                .toList();

        int from = (int) Math.min(pageable.getOffset(), rows.size());
        int to = Math.min(from + pageable.getPageSize(), rows.size());
        return new PageImpl<>(rows.subList(from, to), pageable, rows.size());
    }

    /**
     * <p>Ties break on id, ascending, whichever way the sort runs — the same
     * stable-ordering guarantee {@code PageableFactory} appends to every database
     * sort (contract §2.7). Without it, two students on 0 minutes could swap
     * places between page 1 and page 2 and be shown twice or not at all.</p>
     */
    private static Comparator<StudentListItemDto> comparatorFor(String property, Sort.Order order) {
        Comparator<StudentListItemDto> by = switch (property) {
            case "gradeLevel" -> Comparator.comparingInt(StudentListItemDto::gradeLevel);
            case "completedMinutes" -> Comparator.comparingLong(StudentListItemDto::completedMinutes);
            case "timeCoinBalance" -> Comparator.comparingLong(StudentListItemDto::timeCoinBalance);
            default -> throw new IllegalStateException("Not a computed sort: " + property);
        };
        if (order != null && order.isDescending()) {
            by = by.reversed();
        }
        return by.thenComparing(StudentListItemDto::id);
    }

    private Map<String, Long> minutesFor(List<Student> students, ProgressYardstick yardstick) {
        if (yardstick == null) {
            return Map.of();
        }
        return progressQueryRepository.approvedMinutesByStudent(
                students.stream().map(Student::getId).toList(), yardstick.from(), yardstick.to());
    }

    private Map<String, Long> balancesFor(List<Student> students) {
        return progressQueryRepository.balanceByStudent(students.stream().map(Student::getId).toList());
    }

    /**
     * The year the progress is measured over and the rule it is measured against.
     *
     * <p>Null when either is missing — no current academic year, or no rule in
     * effect. The list still renders in that case with zeroed progress, rather
     * than failing: a roster is useful without a completion figure, and the
     * progress endpoint already reports the same two conditions as faults where
     * they actually matter.</p>
     */
    private ProgressYardstick progressYardstick() {
        AcademicYear year = academicYearRepository.findAll().stream()
                .filter(AcademicYear::isCurrent).findFirst().orElse(null);
        if (year == null) {
            return null;
        }
        return ruleResolutionService.resolveEffectiveAt(Instant.now())
                .map(rule -> new ProgressYardstick(year.getStartDate(), year.getEndDate(), rule))
                .orElse(null);
    }

    /**
     * Turns a bucket name into the set of students in it.
     *
     * <p>Two queries rather than one clever one: the candidates this filter could
     * match, then their approved minutes. The roster this is built for is under
     * two hundred students (`Docs/implementation/01-Overview.md`'s sizing), so
     * reading it to answer "who is behind" costs less than owning a second copy
     * of the list query's scope, filter, sort and paging logic in SQL — and that
     * second copy is the thing that would drift.</p>
     */
    private Specification<Student> progressSpecification(
            Specification<Student> scopeSpec, Specification<Student> filterSpec, String progressStatus,
            Map<String, Grade> grades, ProgressYardstick yardstick) {
        if (yardstick == null) {
            // Nothing to measure against: match nobody rather than everybody. An
            // unanswerable filter that silently returns the whole roster is the
            // failure this issue is about, in the opposite direction.
            return (root, query, cb) -> cb.disjunction();
        }
        List<Student> candidates = studentRepository.findAll(FilterSpecifications.and(scopeSpec, filterSpec));
        Map<String, Long> minutes = progressQueryRepository.approvedMinutesByStudent(
                candidates.stream().map(Student::getId).toList(), yardstick.from(), yardstick.to());

        Set<String> matched = candidates.stream()
                .filter(s -> matchesBucket(progressStatus, ratioOf(s, grades, minutes, yardstick)))
                .map(Student::getId)
                .collect(Collectors.toSet());
        if (matched.isEmpty()) {
            return (root, query, cb) -> cb.disjunction();
        }
        return (root, query, cb) -> root.get("id").in(matched);
    }

    /**
     * Completion as a fraction of what this student's stage is asked for.
     *
     * <p>A zero requirement yields zero rather than dividing — it means no rule
     * covers this grade, and "infinitely behind" is not a more useful answer than
     * "not measured".</p>
     */
    private double ratioOf(Student student, Map<String, Grade> grades,
                            Map<String, Long> minutes, ProgressYardstick yardstick) {
        Grade grade = grades.get(student.getGradeId());
        int required = yardstick.rule().annualRequiredMinutesForGradeLevel(grade != null ? grade.getSequence() : 0);
        if (required <= 0) {
            return 0d;
        }
        return minutes.getOrDefault(student.getId(), 0L) / (double) required;
    }

    /**
     * The three buckets the Student Management filter offers.
     *
     * <p>They overlap deliberately: {@code critical} is a subset of {@code behind},
     * because a student at 10% is both, and a filter offering "behind" that
     * excluded the worst cases would be actively misleading. An unknown value
     * matches nobody rather than everybody — see {@code progressSpecification}.</p>
     */
    private static boolean matchesBucket(String bucket, double ratio) {
        return switch (bucket) {
            case "onTrack" -> ratio >= 0.5;
            case "behind" -> ratio < 0.5;
            case "critical" -> ratio < 0.25;
            default -> false;
        };
    }

    private record ProgressYardstick(java.time.LocalDate from, java.time.LocalDate to, EffectiveRule rule) {
    }

    /**
     * {@code filter[status]} — but an absent filter means <b>current students</b>,
     * not everyone (issue 114).
     *
     * <p>Graduation leaves {@code gradeId} on the grade the student finished
     * (`14-AnnualRollover.md` §3.1), so from the first rollover onward a list that
     * includes graduates puts two cohorts in "grade 8" at once, and anyone
     * counting their grade 8 gets roughly double with nothing on screen saying
     * why. That is a wrong answer rather than extra context, which is why this
     * narrows on an absent parameter where {@code GET /projects} deliberately does
     * not (issue 112): there the extra rows were harmless history, here they
     * collide inside a single number.</p>
     *
     * <p>Naming the status explicitly still reaches every value, {@code GRADUATED}
     * included — the cohort is hidden by default, never unreachable, because data
     * you cannot get back to is worse than a longer list.</p>
     */
    private Specification<Student> statusSpecification(String status) {
        if (status != null && !status.isBlank()) {
            return FilterSpecifications.equalToEnum("status", StudentStatus.class, status);
        }
        return (root, query, cb) -> cb.notEqual(root.get("status"), StudentStatus.GRADUATED);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> activeStudentIdsInGrades(Collection<String> gradeIds) {
        if (gradeIds == null || gradeIds.isEmpty()) {
            return List.of();
        }
        return studentRepository.findByGradeIdInAndStatus(gradeIds, StudentStatus.ACTIVE).stream()
                .map(Student::getId)
                .toList();
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


    /**
     * Student numbers are stored in one canonical case.
     *
     * The database already prevents case-variant duplicates — the column's
     * collation is case-insensitive, so inserting both HKTZ4HKV and hktz4hkv
     * fails on the unique index. What it does not prevent is the two forms
     * being stored by different imports, which would show a mixed-case column
     * on screen and, worse, bake inconsistent casing into service record
     * numbers, which embed the student number and are permanent once issued.
     *
     * Uppercase because Crockford base32 specifies uppercase for output.
     * Locale.ROOT avoids the Turkish dotless-i trap.
     */
    private static String canonicalStudentNumber(String raw) {
        return raw == null ? null : raw.trim().toUpperCase(Locale.ROOT);
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
        String studentNumber = canonicalStudentNumber(request.studentNumber());
        if (studentRepository.existsByStudentNumber(studentNumber)) {
            throw ApiException.stateConflict("Student number is already in use.");
        }
        requireGrade(request.gradeId());
        requireClass(request.classId());

        Instant now = Instant.now();
        Student student = new Student(OpaqueIds.generate("std"), studentNumber, request.displayName(),
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
        if (request.studentNumber() != null) {
            String canonical = canonicalStudentNumber(request.studentNumber());
            // Compared case-insensitively so that re-submitting the same number
            // in a different case is a no-op rather than a self-conflict: the
            // existsBy check below would otherwise match this very student's own
            // row under the column's case-insensitive collation.
            if (!canonical.equalsIgnoreCase(student.getStudentNumber())) {
                if (studentRepository.existsByStudentNumber(canonical)) {
                    throw ApiException.stateConflict("Student number is already in use.");
                }
            }
            student.setStudentNumber(canonical);
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

    @Override
    @Transactional(readOnly = true)
    public Map<String, StudentPlacement> placementsOf(List<String> studentIds) {
        List<String> ids = studentIds.stream().filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Student> students = studentRepository.findAllById(ids);
        if (students.isEmpty()) {
            return Map.of();
        }
        Map<String, Grade> grades = gradeRepository.findAll().stream()
                .collect(Collectors.toMap(Grade::getId, Function.identity()));
        Map<String, SchoolClass> classes = classRepository.findAll().stream()
                .collect(Collectors.toMap(SchoolClass::getId, Function.identity()));
        return students.stream().collect(Collectors.toMap(Student::getId, s -> new StudentPlacement(
                s.getId(), s.getGradeId(), gradeLevelOf(s, grades), s.getClassId(), classNameOf(s, classes))));
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

    private StudentListItemDto toListItem(Student s, Map<String, Grade> grades, Map<String, SchoolClass> classes,
                                           Map<String, Long> minutes, Map<String, Long> balances,
                                           ProgressYardstick yardstick) {
        Grade grade = grades.get(s.getGradeId());
        SchoolClass schoolClass = classes.get(s.getClassId());
        int gradeLevel = grade != null ? grade.getSequence() : 0;
        int required = yardstick == null ? 0 : yardstick.rule().annualRequiredMinutesForGradeLevel(gradeLevel);
        return new StudentListItemDto(
                s.getId(), s.getStudentNumber(), s.getDisplayName(),
                s.getGradeId(), gradeLevel,
                s.getClassId(), schoolClass != null ? schoolClass.getName() : "",
                s.getStatus().name(), s.getGraduationYear(),
                minutes.getOrDefault(s.getId(), 0L), required,
                balances.getOrDefault(s.getId(), 0L), s.getVersion());
    }

    private record StudentSummaryForAudit(String studentNumber, String displayName, String status) {
        StudentSummaryForAudit(Student s) {
            this(s.getStudentNumber(), s.getDisplayName(), s.getStatus().name());
        }
    }
}
