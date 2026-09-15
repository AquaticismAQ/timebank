package cn.timebank.organization;

import cn.timebank.common.error.ApiException;
import cn.timebank.organization.application.ServiceCategoryService;
import cn.timebank.organization.application.ServiceCategoryService.CreateServiceCategoryRequest;
import cn.timebank.organization.application.ServiceCategoryService.UpdateServiceCategoryRequest;
import cn.timebank.organization.application.dto.ServiceCategoryDto;
import cn.timebank.project.application.ProjectService;
import cn.timebank.project.application.dto.ProjectRequests.CreateProjectRequest;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue 078's management surface. The interesting case is delete: three FKs
 * (service_record, project, event_proposal) point at service_category, so
 * deleting a category that is in use must fail as a readable conflict rather
 * than an opaque 500 from the database.
 */
class ServiceCategoryManagementIT extends AbstractIntegrationTest {

    @Autowired
    private ServiceCategoryService service;

    @Autowired
    private ProjectService projectService;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private ServiceCategoryDto createOne() {
        String suffix = SEQ.incrementAndGet() + "_" + System.nanoTime();
        return service.create(new CreateServiceCategoryRequest("测试服务" + suffix, "Test Service " + suffix),
                "usr_test_admin", "en");
    }

    @Test
    void bothNamesAreStoredAndReturnedRegardlessOfTheCallersLanguage() {
        ServiceCategoryDto created = createOne();

        // The resolved `name` follows the caller, but both raw names travel with it
        // so the management screen can edit either one (issue 078).
        assertThat(created.name()).isEqualTo(created.nameEn());
        assertThat(created.nameEn()).startsWith("Test Service ");
        assertThat(created.nameZh()).startsWith("测试服务");
        assertThat(created.code()).startsWith("cat_test_service_");
        assertThat(created.active()).isTrue();

        assertThat(service.list("zh-CN")).filteredOn(c -> c.id().equals(created.id()))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.name()).isEqualTo(created.nameZh());
                    assertThat(c.nameEn()).isEqualTo(created.nameEn());
                });
    }

    @Test
    void updateChangesNamesAndActiveFlag() {
        ServiceCategoryDto created = createOne();

        ServiceCategoryDto updated = service.update(created.id(),
                new UpdateServiceCategoryRequest("更新后的服务", "Updated Service", false),
                "usr_test_admin", "en");

        assertThat(updated.nameZh()).isEqualTo("更新后的服务");
        assertThat(updated.nameEn()).isEqualTo("Updated Service");
        assertThat(updated.active()).isFalse();
    }

    /** Clearing `active` has to actually retire the category from pickers. */
    @Test
    void aDeactivatedCategoryLeavesTheSelectableListButNotTheFullOne() {
        ServiceCategoryDto created = createOne();

        service.update(created.id(), new UpdateServiceCategoryRequest(null, null, false), "usr_test_admin", "en");

        assertThat(service.list("en")).extracting(ServiceCategoryDto::id).contains(created.id());
        assertThat(service.listSelectable("en")).extracting(ServiceCategoryDto::id).doesNotContain(created.id());
    }

    @Test
    void anUnusedCategoryCanBeDeleted() {
        ServiceCategoryDto created = createOne();

        service.delete(created.id(), "usr_test_admin");

        assertThat(service.list("en")).extracting(ServiceCategoryDto::id).doesNotContain(created.id());
    }

    /**
     * The IT schema starts empty of projects/records, so a seeded category is
     * deletable there — the reference has to be created inside the test for this
     * to prove anything.
     */
    @Test
    void aCategoryInUseCannotBeDeletedAndSaysWhy() {
        ServiceCategoryDto created = createOne();
        Instant start = Instant.now().plus(5, ChronoUnit.DAYS);
        projectService.createDraft(new CreateProjectRequest(
                "Category In Use", "summary", "description", created.id(), null, null, "ON_CAMPUS",
                start, start.plusSeconds(7200), "Hall", 10, 120, null, null,
                Instant.now().minusSeconds(60), start), "usr_test_admin", false, "zh-CN");

        assertThatThrownBy(() -> service.delete(created.id(), "usr_test_admin"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("in use");

        assertThat(service.list("en")).extracting(ServiceCategoryDto::id).contains(created.id());
    }

    /** Either name matching an existing category counts as a duplicate. */
    @Test
    void aDuplicateEnglishNameIsRejectedEvenWithADifferentChineseName() {
        ServiceCategoryDto created = createOne();

        assertThatThrownBy(() -> service.create(
                new CreateServiceCategoryRequest("完全不同的名字", created.nameEn().toUpperCase()), "usr_test_admin", "en"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void aDuplicateChineseNameIsRejectedEvenWithADifferentEnglishName() {
        ServiceCategoryDto created = createOne();

        assertThatThrownBy(() -> service.create(
                new CreateServiceCategoryRequest("  " + created.nameZh() + "  ", "A Completely Different Name"),
                "usr_test_admin", "en"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already exists");
    }

    /** Renaming is the other way to collide, so update enforces the same rule. */
    @Test
    void renamingOntoAnotherCategorysNameIsRejected() {
        ServiceCategoryDto first = createOne();
        ServiceCategoryDto second = createOne();

        assertThatThrownBy(() -> service.update(second.id(),
                new UpdateServiceCategoryRequest(null, first.nameEn(), null), "usr_test_admin", "en"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already exists");

        // ...but re-saving a category's own name is not a collision with itself.
        assertThat(service.update(second.id(), new UpdateServiceCategoryRequest(second.nameZh(), second.nameEn(), null),
                "usr_test_admin", "en").nameEn()).isEqualTo(second.nameEn());
    }

    @Test
    void theCodeIsDerivedFromTheEnglishNameAndStaysUniqueOnCollision() {
        ServiceCategoryDto first = service.create(
                new CreateServiceCategoryRequest("代码测试", "Code Derivation Test"), "usr_test_admin", "en");
        assertThat(first.code()).isEqualTo("cat_code_derivation_test");

        // Same English name is refused, so force the slug collision through a
        // different name that slugs to the same thing.
        ServiceCategoryDto second = service.create(
                new CreateServiceCategoryRequest("代码测试二", "Code  Derivation, Test"), "usr_test_admin", "en");
        assertThat(second.code()).startsWith("cat_code_derivation_test_").isNotEqualTo(first.code());
    }
}
