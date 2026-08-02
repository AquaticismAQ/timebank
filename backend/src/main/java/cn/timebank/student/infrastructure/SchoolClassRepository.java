package cn.timebank.student.infrastructure;

import cn.timebank.student.domain.SchoolClass;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SchoolClassRepository extends JpaRepository<SchoolClass, String> {

    List<SchoolClass> findByAcademicYearId(String academicYearId);

    Optional<SchoolClass> findByCodeAndAcademicYearId(String code, String academicYearId);
}
