package cn.timebank.file.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Real checks: declared-vs-actual size, a maximum size ceiling, and a
 * magic-byte sniff confirming the declared MIME type against the file's
 * actual signature (a mismatch is exactly the kind of thing a renamed
 * malicious upload relies on). No virus scanning — see {@link FileScanner}'s
 * Javadoc.
 */
@Component
class BasicFileScanner implements FileScanner {

    private static final Map<String, byte[]> SIGNATURES = Map.of(
            "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
            "image/png", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47},
            "application/pdf", new byte[]{0x25, 0x50, 0x44, 0x46}); // "%PDF"

    private final long maxSizeBytes;

    BasicFileScanner(@Value("${timebank.file.max-size-bytes:20971520}") long maxSizeBytes) {
        this.maxSizeBytes = maxSizeBytes;
    }

    @Override
    public ScanResult scan(byte[] header, long actualSizeBytes, long declaredSizeBytes, String declaredMimeType) {
        if (actualSizeBytes <= 0) {
            return ScanResult.rejected("EMPTY_FILE");
        }
        if (actualSizeBytes > maxSizeBytes) {
            return ScanResult.rejected("FILE_TOO_LARGE");
        }
        if (actualSizeBytes != declaredSizeBytes) {
            return ScanResult.rejected("SIZE_MISMATCH");
        }
        byte[] signature = SIGNATURES.get(declaredMimeType);
        if (signature == null) {
            return ScanResult.rejected("UNSUPPORTED_MIME_TYPE");
        }
        if (header.length < signature.length || !startsWith(header, signature)) {
            return ScanResult.rejected("MIME_MISMATCH");
        }
        return ScanResult.ok();
    }

    private boolean startsWith(byte[] data, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
