package cn.timebank.file.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Test-stage-only adapter behind {@link FileStorage} — a relative path
 * (resolved against the process working directory, `/opt/timebank-backend`
 * under systemd) rather than MinIO/OSS. Disk space is explicitly not a
 * concern for this pass's volume (see `14-Backend-B3F-CloudFileStorage.md`).
 *
 * Every `storageKey` the application passes is built from a generated id, so
 * no caller can reach outside the storage directory today. {@link #resolve}
 * still refuses any key that is not a plain file name inside it — a storage
 * class should not depend on every future caller remembering that.
 */
@Component
class LocalFileStorage implements FileStorage {

    private final Path baseDir;

    LocalFileStorage(@Value("${timebank.file.storage-path:data/evidence-storage}") String storagePath) {
        this.baseDir = Path.of(storagePath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create evidence storage directory: " + baseDir, e);
        }
    }

    @Override
    public void store(String storageKey, InputStream content) {
        try {
            Files.copy(content, resolve(storageKey), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store evidence file: " + storageKey, e);
        }
    }

    @Override
    public InputStream open(String storageKey) {
        try {
            return Files.newInputStream(resolve(storageKey));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not open evidence file: " + storageKey, e);
        }
    }

    @Override
    public long size(String storageKey) {
        try {
            return Files.size(resolve(storageKey));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not stat evidence file: " + storageKey, e);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete evidence file: " + storageKey, e);
        }
    }

    /** A file directly inside the storage directory, or an exception — never anywhere else. */
    Path resolve(String storageKey) {
        Path target = baseDir.resolve(storageKey).normalize();
        if (!target.startsWith(baseDir) || !baseDir.equals(target.getParent())) {
            throw new IllegalArgumentException("Invalid storage key: " + storageKey);
        }
        return target;
    }
}
