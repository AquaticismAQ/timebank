package cn.timebank.file.application.dto;

import java.io.InputStream;

public record DownloadResult(InputStream content, String filename, String mimeType) {
}
