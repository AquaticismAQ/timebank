package cn.timebank.student.infrastructure;

import cn.timebank.student.domain.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

/**
 * {@link JpaSpecificationExecutor} is what B1.7's data-scope mechanism will
 * eventually plug into via {@code DataScopeSpecifications} — not used for
 * that yet (no caller determines teacher grade/class scope until B2's
 * endpoints are permission-gated beyond a flat {@code students.read}), but
 * this is the natural first real consumer once that wiring happens.
 */
public interface StudentRepository extends JpaRepository<Student, String>, JpaSpecificationExecutor<Student> {

    Optional<Student> findByStudentNumber(String studentNumber);

    boolean existsByStudentNumber(String studentNumber);
}
