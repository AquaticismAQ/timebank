package cn.timebank.student.infrastructure;

import cn.timebank.student.domain.Grade;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GradeRepository extends JpaRepository<Grade, String> {

    Optional<Grade> findByCode(String code);
}
