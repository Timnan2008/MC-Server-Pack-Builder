package dev.mcspb.core.metadata;

import dev.mcspb.core.model.*;
import java.io.IOException;
import java.util.List;

public interface MetadataParser {
    Loader loader();
    String entryName();
    List<ModDeclaration> parse(byte[] metadata) throws IOException;
}
