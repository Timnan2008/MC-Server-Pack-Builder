package dev.mcspb.core.scan;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.function.BiConsumer;

interface PackFileSource {
    void download(List<String> urls, Path target, String sha512, long expectedBytes) throws IOException;
    default void download(List<String> urls, Path target, String sha512, long expectedBytes,
                          LongConsumer onBytes) throws IOException {
        download(urls, target, sha512, expectedBytes);
        if (expectedBytes > 0) onBytes.accept(expectedBytes);
    }
    default void download(List<String> urls, Path target, String sha512, long expectedBytes,
                          BiConsumer<Long, Long> onBytes) throws IOException {
        download(urls, target, sha512, expectedBytes,
                (LongConsumer) delta -> onBytes.accept(delta, expectedBytes));
    }
    void verifyLocal(Path file, String sha512, long expectedBytes) throws IOException;
    byte[] getSmall(URI uri, int maxBytes) throws IOException;
}
