package cn.timebank.file.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A storage key may name a file inside the storage directory and nothing else. */
class LocalFileStorageTest {

    @TempDir
    Path root;

    @Test
    void storesAndReadsBackAPlainKey() throws Exception {
        LocalFileStorage storage = new LocalFileStorage(root.resolve("store").toString());
        storage.store("ev_abc123", new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

        try (InputStream in = storage.open("ev_abc123")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
        }
        assertThat(storage.size("ev_abc123")).isEqualTo(5);
    }

    @Test
    void refusesKeysThatLeaveTheStorageDirectory() throws Exception {
        LocalFileStorage storage = new LocalFileStorage(root.resolve("store").toString());
        Path outside = root.resolve("outside.txt");
        Files.writeString(outside, "secret");

        for (String key : new String[] {"../outside.txt", "../../etc/passwd", outside.toString(), "sub/file", "..", ""}) {
            assertThatThrownBy(() -> storage.open(key))
                    .as("key %s", key)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        // Nothing outside was touched by a refused delete either.
        assertThatThrownBy(() -> storage.delete("../outside.txt")).isInstanceOf(IllegalArgumentException.class);
        assertThat(outside).exists();
    }
}
