package dev.mcspb.core.scan;

import dev.mcspb.core.model.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/** Reads NeoForge @Mod entry points without loading or executing JAR classes. */
final class NeoForgeEntryPointAnalyzer {
    private static final String MOD = "Lnet/neoforged/fml/common/Mod;";
    private static final String DIST = "Lnet/neoforged/api/distmarker/Dist;";
    private static final int MAX_CLASS_BYTES = 2_000_000;

    private NeoForgeEntryPointAnalyzer() { }

    static BytecodeAnalysisResult analyze(ZipFile zip) {
        List<ModEntryPoint> found = new ArrayList<>();
        boolean[] complete = {true};
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (entry.isDirectory() || !name.endsWith(".class") ||
                    name.startsWith("META-INF/versions/")) continue;
            if (entry.getSize() > MAX_CLASS_BYTES) { complete[0] = false; continue; }
            try (InputStream in = zip.getInputStream(entry)) {
                byte[] bytes = in.readNBytes(MAX_CLASS_BYTES + 1);
                if (bytes.length > MAX_CLASS_BYTES) { complete[0] = false; continue; }
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                    private String className;

                    @Override public void visit(int version, int access, String internalName,
                                                 String signature, String superName, String[] interfaces) {
                        className = internalName.replace('/', '.');
                    }

                    @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        if (!MOD.equals(descriptor)) return null;
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            private String modId;
                            private final EnumSet<PhysicalSide> sides = EnumSet.noneOf(PhysicalSide.class);
                            private boolean explicit;
                            private boolean valid = true;

                            @Override public void visit(String key, Object value) {
                                if ("value".equals(key) && value instanceof String id) modId = id;
                                else if ("dist".equals(key)) valid = false;
                            }

                            @Override public void visitEnum(String key, String desc, String value) {
                                if ("dist".equals(key)) { explicit = true; addSide(desc, value); }
                            }

                            @Override public AnnotationVisitor visitArray(String key) {
                                if (!"dist".equals(key)) return null;
                                explicit = true;
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override public void visitEnum(String ignored, String desc, String value) {
                                        addSide(desc, value);
                                    }
                                    @Override public void visit(String ignored, Object value) { valid = false; }
                                };
                            }

                            private void addSide(String desc, String value) {
                                if (!DIST.equals(desc)) { valid = false; return; }
                                try { sides.add(PhysicalSide.valueOf(value)); }
                                catch (IllegalArgumentException e) { valid = false; }
                            }

                            @Override public void visitEnd() {
                                if (modId == null || modId.isBlank() || !valid ||
                                        (explicit && sides.isEmpty())) { complete[0] = false; return; }
                                if (!explicit) sides.addAll(EnumSet.allOf(PhysicalSide.class));
                                found.add(new ModEntryPoint(modId, className, sides, explicit));
                            }
                        };
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            } catch (IOException | RuntimeException e) {
                complete[0] = false;
            }
        }
        return new BytecodeAnalysisResult(found, complete[0]);
    }
}
