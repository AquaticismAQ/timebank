package cn.timebank.file.application;

import java.io.InputStream;

/**
 * D9's storage seam. {@code storageKey} is opaque (in practice, the
 * file's own id) — no implementation may derive meaning from it beyond
 * "a name in this store." Swapping the adapter (this pass's {@code
 * LocalFileStorage} for a future {@code S3FileStorage}, tracked in
 * `14-Backend-B3F-CloudFileStorage.md`) requires no change to any caller.
 */
public interface FileStorage {

    void store(String storageKey, InputStream content);

    InputStream open(String storageKey);

    long size(String storageKey);

    void delete(String storageKey);
}
