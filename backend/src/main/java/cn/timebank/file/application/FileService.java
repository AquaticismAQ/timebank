package cn.timebank.file.application;

import cn.timebank.file.application.dto.DownloadResult;
import cn.timebank.file.application.dto.FileMetadataDto;
import cn.timebank.file.application.dto.FileRequests.CreateUploadIntentRequest;
import cn.timebank.file.application.dto.UploadIntentDto;
import org.springframework.security.core.Authentication;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/** The `file` module's published interface (Design.md §6). Contract §4.6. */
public interface FileService {

    UploadIntentDto createUploadIntent(CreateUploadIntentRequest request, String actorId);

    void receiveContent(String fileId, String actorId, InputStream content);

    FileMetadataDto complete(String fileId, String actorId);

    FileMetadataDto getMetadata(String fileId, Authentication auth);

    DownloadResult downloadContent(String token);

    void delete(String fileId, Authentication auth);

    /**
     * B3.3/B3.5: {@code servicerecord}'s submission validation calls this to
     * confirm every referenced file is {@code READY} before allowing submit
     * — a cross-module call through this application interface, never a
     * reach into `file.domain`/`file.infrastructure`. A fileId absent from
     * the result (deleted, or never existed) must be treated as not-ready.
     */
    Map<String, String> statusesOf(List<String> fileIds);
}
