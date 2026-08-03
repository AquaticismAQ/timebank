package cn.timebank.file.application;

/**
 * B3.4's `POST /files/{id}/complete` validation step. {@link
 * BasicFileScanner} does real magic-byte/MIME/size checks; it does
 * <strong>not</strong> scan for malware — that gap is deliberate and
 * tracked (`14-Backend-B3F-CloudFileStorage.md` B3F.6), not hidden behind
 * a scanner that pretends to be real.
 */
public interface FileScanner {

    ScanResult scan(byte[] header, long actualSizeBytes, long declaredSizeBytes, String declaredMimeType);

    record ScanResult(boolean accepted, String rejectionReason) {

        public static ScanResult ok() {
            return new ScanResult(true, null);
        }

        public static ScanResult rejected(String reason) {
            return new ScanResult(false, reason);
        }
    }
}
