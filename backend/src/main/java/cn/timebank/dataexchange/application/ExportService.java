package cn.timebank.dataexchange.application;

import cn.timebank.dataexchange.application.dto.DataExchangeRequests.CreateExportRequest;
import cn.timebank.dataexchange.application.dto.JobDto;
import cn.timebank.file.application.dto.DownloadResult;
import org.springframework.security.core.Authentication;

/** Contract §4.13 — `exports.create` to start one, creator-or-admin to poll/download it. */
public interface ExportService {

    JobDto create(CreateExportRequest request, String actorId);

    JobDto get(String jobId, Authentication auth);

    String issueDownloadUrl(String jobId, Authentication auth);

    DownloadResult download(String token);
}
