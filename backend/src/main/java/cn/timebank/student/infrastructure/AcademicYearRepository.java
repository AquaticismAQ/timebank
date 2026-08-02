package cn.timebank.student.infrastructure;

import cn.timebank.student.domain.AcademicYear;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AcademicYearRepository extends JpaRepository<AcademicYear, String> {
}
