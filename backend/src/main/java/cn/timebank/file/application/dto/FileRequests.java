package cn.timebank.file.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** Contract §13.4: `POST /files/upload-intents` body. */
public final class FileRequests {

    private FileRequests() {
    }

    public record CreateUploadIntentRequest(
            @NotBlank String filename, @NotBlank String mimeType, @Positive long sizeBytes, @NotBlank String purpose) {
    }
}
