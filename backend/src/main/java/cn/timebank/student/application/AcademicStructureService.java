package cn.timebank.student.application;

import cn.timebank.student.application.dto.AcademicYearDto;
import cn.timebank.student.application.dto.ClassDto;
import cn.timebank.student.application.dto.GradeDto;

import java.util.List;

/** Contract §4.9's dictionary reads: `/academic-years`, `/grades`, `/classes`. */
public interface AcademicStructureService {

    List<AcademicYearDto> listAcademicYears();

    List<GradeDto> listGrades(String resolvedLanguage);

    List<ClassDto> listClasses();
}
