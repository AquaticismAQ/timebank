package cn.timebank.organization;

import cn.timebank.organization.application.OrganizationService;
import cn.timebank.organization.application.dto.OrganizationDto;
import cn.timebank.organization.application.dto.OrganizationRequests.CreateOrganizationRequest;
import cn.timebank.project.application.ProjectService;
import cn.timebank.project.application.dto.ProjectDto;
import cn.timebank.project.application.dto.ProjectRequests.CreateProjectRequest;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** B4 fills in B2's `projectCount = 0` placeholder — proven against a real project now that `project` exists. */
class OrganizationProjectCountIT extends AbstractIntegrationTest {

    @Autowired
    private OrganizationService organizationService;
    @Autowired
    private ProjectService projectService;

    @Test
    void projectCountReflectsRealNonArchivedProjectsForThatOrganization() {
        OrganizationDto org = organizationService.create(
                new CreateOrganizationRequest("Test Partner Org", "COMMUNITY", null, null, null, null),
                "usr_test_admin");
        assertThat(org.projectCount()).isZero();

        Instant start = Instant.now().plus(5, ChronoUnit.DAYS);
        CreateProjectRequest request = new CreateProjectRequest(
                "Org Project", "summary", "description", "cat_campus", org.id(),
                start, start.plusSeconds(7200), "Hall", 10, 100, null, null,
                Instant.now(), start);
        projectService.createDraft(request, "usr_test_admin", "zh-CN");

        OrganizationDto reread = organizationService.get(org.id());
        assertThat(reread.projectCount()).isEqualTo(1);
    }
}
