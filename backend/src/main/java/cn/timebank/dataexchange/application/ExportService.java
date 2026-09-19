package cn.timebank.dataexchange.application;

import cn.timebank.dataexchange.application.dto.DataExchangeRequests.CreateExportRequest;
import cn.timebank.dataexchange.application.dto.ExportTypeDto;
import cn.timebank.dataexchange.application.dto.JobDto;
import cn.timebank.file.application.dto.DownloadResult;
import org.springframework.security.core.Authentication;

import java.util.List;

/** Contract §4.13 — one {@code exports.<type>} permission to start one, creator-or-admin to poll/download it. */
public interface ExportService {

    /**
     * Takes the whole {@link Authentication} rather than just an actor id, because
     * creating an export now resolves the caller's data scope (§2.5) and that
     * needs their grade or class binding, not only their identity. Resolving it
     * here, on the request thread, is deliberate — see {@code ExportJobParameters}.
     */
    JobDto create(CreateExportRequest request, Authentication auth);

    /** The export types this caller may ask for, with the filters each accepts (§2.6.1). */
    List<ExportTypeDto> availableTypes(Authentication auth);

    JobDto get(String jobId, Authentication auth);

    String issueDownloadUrl(String jobId, Authentication auth);

    DownloadResult download(String token);
}
