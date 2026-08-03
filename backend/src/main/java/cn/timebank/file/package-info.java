/**
 * Upload intents, metadata, scanning, short-lived access URLs (B3.4/B3.5).
 * The {@link cn.timebank.file.application.FileStorage} port is implemented
 * by a local-disk adapter for this test-stage pass — a deliberate,
 * documented substitution for D9's S3-compatible recommendation, not a
 * reversal of it (`14-Backend-B3F-CloudFileStorage.md` tracks the move to
 * MinIO/OSS). {@link cn.timebank.file.application.FileScanner} does real
 * magic-byte/MIME/size checks; it does not scan for malware.
 */
package cn.timebank.file;
