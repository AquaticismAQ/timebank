package cn.timebank.student;

import cn.timebank.organization.application.RequestLanguageService;
import cn.timebank.student.application.AcademicStructureService;
import cn.timebank.student.application.dto.GradeDto;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B2.5's exit criterion, proven against real MySQL: the same request,
 * distinguished only by {@code Accept-Language}, returns identically-coded
 * rows with different display names, and a missing translation falls back
 * rather than going null/empty.
 */
class LocalizationIT extends AbstractIntegrationTest {

    @Autowired
    private AcademicStructureService academicStructureService;

    @Autowired
    private RequestLanguageService requestLanguageService;

    @Test
    void sameGradesReturnIdenticalCodesButDifferentNamesByLanguage() {
        List<GradeDto> zh = academicStructureService.listGrades("zh-CN");
        List<GradeDto> en = academicStructureService.listGrades("en");

        assertThat(zh).isNotEmpty();
        Map<String, String> zhById = zh.stream().collect(Collectors.toMap(GradeDto::id, GradeDto::name));
        Map<String, String> enById = en.stream().collect(Collectors.toMap(GradeDto::id, GradeDto::name));

        assertThat(zhById.keySet()).isEqualTo(enById.keySet());

        GradeDto grade6Zh = zh.stream().filter(g -> g.id().equals("grade_6")).findFirst().orElseThrow();
        GradeDto grade6En = en.stream().filter(g -> g.id().equals("grade_6")).findFirst().orElseThrow();
        assertThat(grade6Zh.name()).isEqualTo("六年级");
        assertThat(grade6En.name()).isEqualTo("Grade 6");
        assertThat(grade6Zh.name()).isNotEqualTo(grade6En.name());

        // Codes/sequence — the non-display fields — must be identical regardless of language.
        assertThat(grade6Zh.code()).isEqualTo(grade6En.code());
        assertThat(grade6Zh.sequence()).isEqualTo(grade6En.sequence());
    }

    @Test
    void noNameIsEverNullOrBlankRegardlessOfLanguage() {
        List<GradeDto> zh = academicStructureService.listGrades("zh-CN");
        List<GradeDto> en = academicStructureService.listGrades("en");
        assertThat(zh).allSatisfy(g -> assertThat(g.name()).isNotBlank());
        assertThat(en).allSatisfy(g -> assertThat(g.name()).isNotBlank());
    }

    @Test
    void languageResolutionPrefersHeaderThenPreferenceThenSchoolDefault() {
        // Header wins outright.
        assertThat(requestLanguageService.resolve("en", "zh-CN")).isEqualTo("en");
        // No header: falls back to the caller's stated preference.
        assertThat(requestLanguageService.resolve(null, "en")).isEqualTo("en");
        // Neither header nor preference: falls back to the seeded school default (zh-CN).
        assertThat(requestLanguageService.resolve(null, null)).isEqualTo("zh-CN");
    }
}
