package dev.mcspb.core.scan;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

interface PackFileSource {
    void download(List<String> urls, Path target, String sha512, long expectedBytes) throws IOException;
    void verifyLocal(Path file, String sha512, long expectedBytes) throws IOException;
    byte[] getSmall(URI uri, int maxBytes) throws IOException;
}
