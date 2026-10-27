package cn.timebank.dataexchange.web;

import cn.timebank.common.error.ApiException;
import cn.timebank.common.error.GlobalExceptionHandler;
import cn.timebank.common.web.RequestIdFilter;
import cn.timebank.dataexchange.application.HistoricalImportWindowService;
import cn.timebank.dataexchange.application.ImportService;
import cn.timebank.dataexchange.application.dto.ImportTemplateDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The template download names its file from what the service resolved, never
 * from the request path — the path is user input, and it used to be pasted into
 * the Content-Disposition header (CodeQL java/xss). Security auto-configuration
 * is excluded as in {@code PingControllerTest}; the permission check is not what
 * this slice is about.
 */
@WebMvcTest(controllers = ImportController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
@Import({GlobalExceptionHandler.class, RequestIdFilter.class})
class ImportControllerTemplateTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ImportService importService;

    @MockitoBean
    private HistoricalImportWindowService windowService;

    @Test
    void knownTypeDownloadsUnderTheRuleSetsFileName() throws Exception {
        // A name the path could not have produced, so a header built from the
        // path variable fails here rather than passing by coincidence.
        when(importService.template("students"))
                .thenReturn(new ImportTemplateDto("roster-from-rules-template.csv", "studentNumber,name\nS001,Alex\n"));

        mockMvc.perform(get("/api/v1/imports/templates/students"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"roster-from-rules-template.csv\""))
                .andExpect(content().string("studentNumber,name\nS001,Alex\n"));
    }

    @Test
    void unknownTypeIsA404WithNoFileName() throws Exception {
        when(importService.template(anyString())).thenThrow(ApiException.notFound("Unknown import type"));

        mockMvc.perform(get("/api/v1/imports/templates/{type}", "x\"><script>alert(1)</script>"))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION));
    }
}
