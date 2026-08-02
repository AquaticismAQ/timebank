package cn.timebank.student.application;

import cn.timebank.common.cache.CacheConfig;
import cn.timebank.common.i18n.LocalizedNames;
import cn.timebank.student.application.dto.AcademicYearDto;
import cn.timebank.student.application.dto.ClassDto;
import cn.timebank.student.application.dto.GradeDto;
import cn.timebank.student.domain.Grade;
import cn.timebank.student.domain.SchoolClass;
import cn.timebank.student.infrastructure.AcademicYearRepository;
import cn.timebank.student.infrastructure.GradeRepository;
import cn.timebank.student.infrastructure.SchoolClassRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
class AcademicStructureServiceImpl implements AcademicStructureService {

    private final AcademicYearRepository academicYearRepository;
    private final GradeRepository gradeRepository;
    private final SchoolClassRepository classRepository;

    AcademicStructureServiceImpl(AcademicYearRepository academicYearRepository, GradeRepository gradeRepository,
                                  SchoolClassRepository classRepository) {
        this.academicYearRepository = academicYearRepository;
        this.gradeRepository = gradeRepository;
        this.classRepository = classRepository;
    }

    @Override
    @Transactional(readOnly = true)
    // Explicit key — see organization.application.SchoolInfoServiceImpl
    // .getInfo() for why every no-arg @Cacheable method in this shared cache
    // region needs a distinct one.
    @Cacheable(value = CacheConfig.DICTIONARIES, key = "'academicYears'")
    public List<AcademicYearDto> listAcademicYears() {
        return academicYearRepository.findAll().stream()
                .map(y -> new AcademicYearDto(y.getId(), y.getCode(), y.getStartDate(), y.getEndDate(), y.isCurrent()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = CacheConfig.DICTIONARIES, key = "'grades:' + #resolvedLanguage")
    public List<GradeDto> listGrades(String resolvedLanguage) {
        return gradeRepository.findAll().stream()
                .sorted(Comparator.comparingInt(Grade::getSequence))
                .map(g -> new GradeDto(g.getId(), g.getCode(),
                        LocalizedNames.pick(resolvedLanguage, g.getNameZh(), g.getNameEn()), g.getSequence()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = CacheConfig.DICTIONARIES, key = "'classes:' + #resolvedLanguage")
    public List<ClassDto> listClasses(String resolvedLanguage) {
        return classRepository.findAll().stream()
                .map(c -> new ClassDto(c.getId(), c.getCode(),
                        LocalizedNames.pick(resolvedLanguage, c.getNameZh(), c.getNameEn()),
                        c.getGradeId(), c.getAcademicYearId()))
                .toList();
    }
}
