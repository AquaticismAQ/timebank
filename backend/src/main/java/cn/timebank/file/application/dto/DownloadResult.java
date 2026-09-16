package cn.timebank.file.application.dto;

import java.io.InputStream;

/**
 * @param watermarked whether {@code content} carries a per-viewer watermark.
 *                    The controller uses this to keep the response out of
 *                    shared caches — two viewers must never be served each
 *                    other's marked copy.
 */
public record DownloadResult(InputStream content, String filename, String mimeType, boolean watermarked) {

    /**
     * For downloads that are never watermarked — the {@code dataexchange}
     * module's CSV exports, which are generated per request from data the
     * caller is already entitled to and carry no evidence imagery. Kept so
     * adding the watermark flag did not require touching that module.
     */
    public DownloadResult(InputStream content, String filename, String mimeType) {
        this(content, filename, mimeType, false);
    }
}
