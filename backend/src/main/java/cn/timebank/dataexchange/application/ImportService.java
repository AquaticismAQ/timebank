package cn.timebank.dataexchange.application;

import cn.timebank.dataexchange.application.dto.ImportErrorReportDto;
import cn.timebank.dataexchange.application.dto.ImportTemplateDto;
import cn.timebank.dataexchange.application.dto.JobDto;
import org.springframework.security.core.Authentication;
import org.springframework.web.multipart.MultipartFile;

/** Contract §4.14. Validate-before-commit is mandatory — {@link #commit} rejects a job that hasn't reached {@code VALIDATED}. */
public interface ImportService {

    ImportTemplateDto template(String type);

    JobDto create(String type, MultipartFile file, String actorId);

    JobDto get(String jobId, Authentication auth);

    JobDto validate(String jobId, String actorId);

    ImportErrorReportDto errors(String jobId, Authentication auth);

    JobDto commit(String jobId, String actorId, String idempotencyKey);

    JobDto cancel(String jobId, String actorId);
}
