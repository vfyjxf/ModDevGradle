package net.neoforged.moddevgradle.mcpforge.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;
import net.neoforged.moddevgradle.internal.utils.FileUtils;
import org.jetbrains.annotations.ApiStatus;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.MethodRemapper;
import org.objectweb.asm.commons.Remapper;
import org.tukaani.xz.LZMAInputStream;

@ApiStatus.Internal
public final class LegacyForgeJarProcessor {
    private static final String FORGE_1_12_DEOBF_DATA = "deobfuscation_data-1.12.2.lzma";

    private LegacyForgeJarProcessor() {}

    public static void remapMinecraftReferences(Path jar) throws IOException {
        remapMinecraftReferences(jar, null);
    }

    public static void remapMinecraftReferences(Path jar, Path srgToMcpMappings) throws IOException {
        LegacyForgeMappings mappings;
        Map<String, ClassInfo> classInfos;
        try (var input = new JarFile(jar.toFile(), false, ZipFile.OPEN_READ)) {
            var mappingsEntry = input.getJarEntry(FORGE_1_12_DEOBF_DATA);
            if (mappingsEntry == null) {
                return;
            }

            mappings = readForgeMappings(input, mappingsEntry);
            classInfos = readClassInfos(input);
        }

        if (mappings.classMappings().isEmpty()) {
            return;
        }

        if (srgToMcpMappings != null) {
            mappings = mappings.withSrgToMcp(readSrgToMcpMappings(srgToMcpMappings));
        }
        mappings = mappings.withInheritedMemberMappings(classInfos);

        var tempFile = jar.resolveSibling(jar.getFileName().toString() + ".remapped.tmp");
        try {
            try (var input = new JarFile(jar.toFile(), false, ZipFile.OPEN_READ);
                    var output = new JarOutputStream(Files.newOutputStream(tempFile))) {
                var entries = input.entries();
                var remapper = new LegacyForgeRemapper(mappings);
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    var newEntry = new JarEntry(entry.getName());
                    newEntry.setTime(entry.getTime());
                    output.putNextEntry(newEntry);
                    if (!entry.isDirectory()) {
                        try (var entryInput = input.getInputStream(entry)) {
                            if (shouldProcess(entry.getName())) {
                                output.write(remapClass(entryInput.readAllBytes(), remapper, classInfos));
                            } else {
                                entryInput.transferTo(output);
                            }
                        }
                    }
                    output.closeEntry();
                }
            }
            FileUtils.atomicMove(tempFile, jar);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private static LegacyForgeMappings readForgeMappings(JarFile jar, JarEntry mappingsEntry) throws IOException {
        var classMappings = new HashMap<String, String>();
        var fieldMappings = new HashMap<MemberKey, String>();
        var methodMappings = new HashMap<MemberKey, String>();
        try (var input = new LZMAInputStream(jar.getInputStream(mappingsEntry));
                var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                var parts = line.split("\\s+");
                if (parts.length < 3) {
                    continue;
                }

                if ("CL:".equals(parts[0])) {
                    classMappings.put(parts[1], parts[2]);
                } else if ("FD:".equals(parts[0])) {
                    var source = splitMember(parts[1]);
                    var target = splitMember(parts[2]);
                    fieldMappings.put(new MemberKey(source.owner(), source.name(), null), target.name());
                } else if ("MD:".equals(parts[0]) && parts.length >= 5) {
                    var source = splitMember(parts[1]);
                    var target = splitMember(parts[3]);
                    methodMappings.put(new MemberKey(source.owner(), source.name(), parts[2]), target.name());
                }
            }
        }
        return new LegacyForgeMappings(classMappings, fieldMappings, methodMappings);
    }

    private static SrgToMcpMappings readSrgToMcpMappings(Path mappingsFile) throws IOException {
        var fieldMappings = new HashMap<MemberKey, String>();
        var methodMappings = new HashMap<MemberKey, String>();
        try (var reader = Files.newBufferedReader(mappingsFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                var parts = line.split("\\s+");
                if (parts.length < 3) {
                    continue;
                }

                if ("FD:".equals(parts[0])) {
                    var source = splitMember(parts[1]);
                    var target = splitMember(parts[2]);
                    fieldMappings.put(new MemberKey(source.owner(), source.name(), null), target.name());
                } else if ("MD:".equals(parts[0]) && parts.length >= 5) {
                    var source = splitMember(parts[1]);
                    var target = splitMember(parts[3]);
                    methodMappings.put(new MemberKey(source.owner(), source.name(), parts[2]), target.name());
                }
            }
        }
        return new SrgToMcpMappings(fieldMappings, methodMappings);
    }

    private static Map<String, ClassInfo> readClassInfos(JarFile jar) throws IOException {
        var classInfos = new HashMap<String, ClassInfo>();
        var entries = jar.entries();
        while (entries.hasMoreElements()) {
            var entry = entries.nextElement();
            if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                continue;
            }

            try (var input = jar.getInputStream(entry)) {
                var reader = new ClassReader(input.readAllBytes());
                var methods = new ArrayList<MethodInfo>();
                var fields = new ArrayList<FieldInfo>();
                reader.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public org.objectweb.asm.FieldVisitor visitField(
                            int access,
                            String name,
                            String descriptor,
                            String signature,
                            Object value) {
                        fields.add(new FieldInfo(access, name, descriptor));
                        return null;
                    }

                    @Override
                    public org.objectweb.asm.MethodVisitor visitMethod(
                            int access,
                            String name,
                            String descriptor,
                            String signature,
                            String[] exceptions) {
                        methods.add(new MethodInfo(access, name, descriptor));
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                classInfos.put(reader.getClassName(), new ClassInfo(
                        reader.getClassName(),
                        reader.getSuperName(),
                        List.of(reader.getInterfaces()),
                        List.copyOf(fields),
                        List.copyOf(methods)));
            }
        }
        return classInfos;
    }

    private static Member splitMember(String value) {
        var separator = value.lastIndexOf('/');
        return new Member(value.substring(0, separator), value.substring(separator + 1));
    }

    private static boolean shouldProcess(String entryName) {
        return entryName.endsWith(".class") && shouldProcessClass(entryName.substring(0, entryName.length() - ".class".length()));
    }

    private static boolean shouldProcessClass(String internalName) {
        return isMinecraftClass(internalName) || isForgeClass(internalName);
    }

    private static boolean shouldRemapClass(String internalName) {
        return isForgeClass(internalName);
    }

    private static boolean isMinecraftClass(String internalName) {
        return internalName.startsWith("net/minecraft/");
    }

    private static boolean isForgeClass(String internalName) {
        return internalName.startsWith("net/minecraftforge/");
    }

    private static byte[] remapClass(byte[] classBytes, LegacyForgeRemapper remapper, Map<String, ClassInfo> classInfos) {
        var reader = new ClassReader(classBytes);
        var writer = newClassWriter(reader.getClassName());
        if (isMinecraftClass(reader.getClassName())) {
            reader.accept(new LegacyMinecraftClassReferenceRemapper(writer, remapper, classInfos), 0);
        } else {
            reader.accept(new LegacyForgeClassRemapper(writer, remapper), 0);
        }
        return writer.toByteArray();
    }

    private static ClassWriter newClassWriter(String className) {
        if (LegacyMinecraftClassReferenceRemapper.needsComputedFrames(className)) {
            return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override
                protected String getCommonSuperClass(String type1, String type2) {
                    return "java/lang/Object";
                }
            };
        }
        return new ClassWriter(0);
    }

    private static final class LegacyForgeClassRemapper extends ClassRemapper {
        private final LegacyForgeRemapper remapper;

        private LegacyForgeClassRemapper(ClassVisitor classVisitor, LegacyForgeRemapper remapper) {
            super(classVisitor, remapper);
            this.remapper = remapper;
        }

        @Override
        protected MethodVisitor createMethodRemapper(MethodVisitor methodVisitor) {
            return new LegacyMethodRemapper(methodVisitor, remapper);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            var method = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (method == null) {
                return null;
            }
            return new MethodVisitor(Opcodes.ASM9, method) {
                @Override
                public void visitLdcInsn(Object value) {
                    if (value instanceof String string) {
                        super.visitLdcInsn(remapper.mapStringConstant(string));
                    } else {
                        super.visitLdcInsn(value);
                    }
                }
            };
        }
    }

    private static final class LegacyMinecraftClassReferenceRemapper extends ClassVisitor {
        private static final String GL_ALLOCATION = "net/minecraft/client/renderer/GLAllocation";
        private static final String CRASH_REPORT_CATEGORY = "net/minecraft/crash/CrashReportCategory";

        private final LegacyForgeRemapper remapper;
        private final Remapper referenceRemapper;
        private String className;

        private LegacyMinecraftClassReferenceRemapper(
                ClassVisitor classVisitor,
                LegacyForgeRemapper remapper,
                Map<String, ClassInfo> classInfos) {
            super(Opcodes.ASM9, classVisitor);
            this.remapper = remapper;
            this.referenceRemapper = new LegacyMinecraftReferenceRemapper(remapper, classInfos);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            className = name;
            super.visit(
                    version,
                    access,
                    remapper.mapType(name),
                    remapper.mapSignature(signature, false),
                    remapper.mapType(superName),
                    interfaces == null ? null : remapper.mapTypes(interfaces));
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            return super.visitField(
                    access,
                    name,
                    remapper.mapDesc(descriptor),
                    remapper.mapSignature(signature, true),
                    remapper.mapValue(value));
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            if (GL_ALLOCATION.equals(className) && name.equals("generateDisplayLists") && descriptor.equals("(I)I")) {
                var method = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (method != null) {
                    writeGenerateDisplayLists(method);
                }
                return null;
            }
            if (CRASH_REPORT_CATEGORY.equals(className)
                    && name.equals("firstTwoElementsOfStackTraceMatch")
                    && descriptor.equals("(Ljava/lang/StackTraceElement;Ljava/lang/StackTraceElement;)Z")) {
                var method = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (method != null) {
                    writeFirstTwoElementsOfStackTraceMatch(method);
                }
                return null;
            }

            var method = super.visitMethod(
                    access,
                    name,
                    remapper.mapMethodDesc(descriptor),
                    remapper.mapSignature(signature, false),
                    exceptions == null ? null : remapper.mapTypes(exceptions));
            return method == null ? null : new LegacyMethodRemapper(method, referenceRemapper);
        }

        @Override
        public void visitInnerClass(String name, String outerName, String innerName, int access) {
            super.visitInnerClass(
                    remapper.mapType(name),
                    outerName == null ? null : remapper.mapType(outerName),
                    innerName,
                    access);
        }

        @Override
        public void visitOuterClass(String owner, String name, String descriptor) {
            super.visitOuterClass(
                    remapper.mapType(owner),
                    name == null ? null : referenceRemapper.mapMethodName(owner, name, descriptor),
                    descriptor == null ? null : remapper.mapMethodDesc(descriptor));
        }

        private void writeGenerateDisplayLists(MethodVisitor method) {
            method.visitCode();

            var success = new org.objectweb.asm.Label();
            var throwFailure = new org.objectweb.asm.Label();
            var retry = new org.objectweb.asm.Label();
            var retryHandler = new org.objectweb.asm.Label();
            var retryEnd = new org.objectweb.asm.Label();
            var retryDone = new org.objectweb.asm.Label();

            method.visitTryCatchBlock(retry, retryEnd, retryHandler, "org/lwjgl/LWJGLException");

            method.visitVarInsn(Opcodes.ILOAD, 0);
            method.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "net/minecraft/client/renderer/GlStateManager",
                    "glGenLists",
                    "(I)I",
                    false);
            method.visitVarInsn(Opcodes.ISTORE, 1);
            method.visitVarInsn(Opcodes.ILOAD, 1);
            method.visitJumpInsn(Opcodes.IFNE, success);

            method.visitLabel(retry);
            method.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/lwjgl/opengl/Display",
                    "isCreated",
                    "()Z",
                    false);
            method.visitJumpInsn(Opcodes.IFEQ, retryEnd);
            method.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/lwjgl/opengl/Display",
                    "getDrawable",
                    "()Lorg/lwjgl/opengl/Drawable;",
                    false);
            method.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE,
                    "org/lwjgl/opengl/Drawable",
                    "makeCurrent",
                    "()V",
                    true);
            method.visitLabel(retryEnd);
            method.visitJumpInsn(Opcodes.GOTO, retryDone);

            method.visitLabel(retryHandler);
            method.visitInsn(Opcodes.POP);

            method.visitLabel(retryDone);
            method.visitVarInsn(Opcodes.ILOAD, 0);
            method.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "net/minecraft/client/renderer/GlStateManager",
                    "glGenLists",
                    "(I)I",
                    false);
            method.visitVarInsn(Opcodes.ISTORE, 1);
            method.visitVarInsn(Opcodes.ILOAD, 1);
            method.visitJumpInsn(Opcodes.IFEQ, throwFailure);

            method.visitLabel(success);
            method.visitVarInsn(Opcodes.ILOAD, 1);
            method.visitInsn(Opcodes.IRETURN);

            method.visitLabel(throwFailure);
            method.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "net/minecraft/client/renderer/GlStateManager",
                    "glGetError",
                    "()I",
                    false);
            method.visitVarInsn(Opcodes.ISTORE, 2);
            method.visitLdcInsn("No error code reported");
            method.visitVarInsn(Opcodes.ASTORE, 3);

            var skipErrorString = new org.objectweb.asm.Label();
            method.visitVarInsn(Opcodes.ILOAD, 2);
            method.visitJumpInsn(Opcodes.IFEQ, skipErrorString);
            method.visitVarInsn(Opcodes.ILOAD, 2);
            method.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/lwjgl/util/glu/GLU",
                    "gluErrorString",
                    "(I)Ljava/lang/String;",
                    false);
            method.visitVarInsn(Opcodes.ASTORE, 3);
            method.visitLabel(skipErrorString);

            method.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
            method.visitInsn(Opcodes.DUP);
            method.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
            method.visitInsn(Opcodes.DUP);
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
            method.visitLdcInsn("glGenLists returned an ID of 0 for a count of ");
            method.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/StringBuilder",
                    "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                    false);
            method.visitVarInsn(Opcodes.ILOAD, 0);
            method.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/StringBuilder",
                    "append",
                    "(I)Ljava/lang/StringBuilder;",
                    false);
            method.visitLdcInsn(", GL error (");
            method.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/StringBuilder",
                    "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                    false);
            method.visitVarInsn(Opcodes.ILOAD, 2);
            method.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/StringBuilder",
                    "append",
                    "(I)Ljava/lang/StringBuilder;",
                    false);
            method.visitLdcInsn("): ");
            method.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/StringBuilder",
                    "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                    false);
            method.visitVarInsn(Opcodes.ALOAD, 3);
            method.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/StringBuilder",
                    "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                    false);
            method.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/StringBuilder",
                    "toString",
                    "()Ljava/lang/String;",
                    false);
            method.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    "java/lang/IllegalStateException",
                    "<init>",
                    "(Ljava/lang/String;)V",
                    false);
            method.visitInsn(Opcodes.ATHROW);

            method.visitMaxs(4, 4);
            method.visitEnd();
        }

        private static boolean needsComputedFrames(String className) {
            return GL_ALLOCATION.equals(className) || CRASH_REPORT_CATEGORY.equals(className);
        }

        private void writeFirstTwoElementsOfStackTraceMatch(MethodVisitor method) {
            method.visitCode();
            var falseLabel = new org.objectweb.asm.Label();
            var compareSecond = new org.objectweb.asm.Label();
            var noSecond = new org.objectweb.asm.Label();

            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitFieldInsn(Opcodes.GETFIELD, CRASH_REPORT_CATEGORY, "stackTrace", "[Ljava/lang/StackTraceElement;");
            method.visitInsn(Opcodes.ARRAYLENGTH);
            method.visitJumpInsn(Opcodes.IFEQ, falseLabel);
            method.visitVarInsn(Opcodes.ALOAD, 1);
            method.visitJumpInsn(Opcodes.IFNULL, falseLabel);
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitFieldInsn(Opcodes.GETFIELD, CRASH_REPORT_CATEGORY, "stackTrace", "[Ljava/lang/StackTraceElement;");
            method.visitInsn(Opcodes.ICONST_0);
            method.visitInsn(Opcodes.AALOAD);
            method.visitVarInsn(Opcodes.ASTORE, 3);

            method.visitVarInsn(Opcodes.ALOAD, 3);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StackTraceElement", "isNativeMethod", "()Z", false);
            method.visitVarInsn(Opcodes.ALOAD, 1);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StackTraceElement", "isNativeMethod", "()Z", false);
            method.visitJumpInsn(Opcodes.IF_ICMPNE, falseLabel);

            writeStackTraceElementStringEquals(method, "getClassName");
            method.visitJumpInsn(Opcodes.IFEQ, falseLabel);
            writeStackTraceElementStringEquals(method, "getFileName");
            method.visitJumpInsn(Opcodes.IFEQ, falseLabel);
            writeStackTraceElementStringEquals(method, "getMethodName");
            method.visitJumpInsn(Opcodes.IFEQ, falseLabel);

            method.visitVarInsn(Opcodes.ALOAD, 2);
            method.visitJumpInsn(Opcodes.IFNONNULL, compareSecond);
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitFieldInsn(Opcodes.GETFIELD, CRASH_REPORT_CATEGORY, "stackTrace", "[Ljava/lang/StackTraceElement;");
            method.visitInsn(Opcodes.ARRAYLENGTH);
            method.visitInsn(Opcodes.ICONST_1);
            method.visitJumpInsn(Opcodes.IF_ICMPGT, falseLabel);
            method.visitJumpInsn(Opcodes.GOTO, noSecond);

            method.visitLabel(compareSecond);
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitFieldInsn(Opcodes.GETFIELD, CRASH_REPORT_CATEGORY, "stackTrace", "[Ljava/lang/StackTraceElement;");
            method.visitInsn(Opcodes.ARRAYLENGTH);
            method.visitInsn(Opcodes.ICONST_1);
            method.visitJumpInsn(Opcodes.IF_ICMPLE, falseLabel);
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitFieldInsn(Opcodes.GETFIELD, CRASH_REPORT_CATEGORY, "stackTrace", "[Ljava/lang/StackTraceElement;");
            method.visitInsn(Opcodes.ICONST_1);
            method.visitInsn(Opcodes.AALOAD);
            method.visitVarInsn(Opcodes.ALOAD, 2);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StackTraceElement", "equals", "(Ljava/lang/Object;)Z", false);
            method.visitJumpInsn(Opcodes.IFEQ, falseLabel);

            method.visitLabel(noSecond);
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitFieldInsn(Opcodes.GETFIELD, CRASH_REPORT_CATEGORY, "stackTrace", "[Ljava/lang/StackTraceElement;");
            method.visitInsn(Opcodes.ICONST_0);
            method.visitVarInsn(Opcodes.ALOAD, 1);
            method.visitInsn(Opcodes.AASTORE);
            method.visitInsn(Opcodes.ICONST_1);
            method.visitInsn(Opcodes.IRETURN);

            method.visitLabel(falseLabel);
            method.visitInsn(Opcodes.ICONST_0);
            method.visitInsn(Opcodes.IRETURN);
            method.visitMaxs(0, 0);
            method.visitEnd();
        }

        private void writeStackTraceElementStringEquals(MethodVisitor method, String getterName) {
            method.visitVarInsn(Opcodes.ALOAD, 3);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StackTraceElement", getterName, "()Ljava/lang/String;", false);
            method.visitVarInsn(Opcodes.ALOAD, 1);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StackTraceElement", getterName, "()Ljava/lang/String;", false);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "equals", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
        }
    }

    private static final class LegacyMethodRemapper extends MethodRemapper {
        private static final String LAMBDA_METAFACTORY = "java/lang/invoke/LambdaMetafactory";
        private static final String METAFACTORY_DESCRIPTOR = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;"
                + "Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;";
        private static final String ALT_METAFACTORY_DESCRIPTOR = "(Ljava/lang/invoke/MethodHandles$Lookup;"
                + "Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;";

        private final Remapper remapper;

        private LegacyMethodRemapper(MethodVisitor methodVisitor, Remapper remapper) {
            super(methodVisitor, remapper);
            this.remapper = remapper;
        }

        @Override
        public void visitInvokeDynamicInsn(
                String name,
                String descriptor,
                Handle bootstrapMethodHandle,
                Object... bootstrapMethodArguments) {
            super.visitInvokeDynamicInsn(
                    mapInvokeDynamicMethodName(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments),
                    descriptor,
                    bootstrapMethodHandle,
                    bootstrapMethodArguments);
        }

        private String mapInvokeDynamicMethodName(
                String name,
                String descriptor,
                Handle bootstrapMethodHandle,
                Object[] bootstrapMethodArguments) {
            if (!isLambdaMetafactory(bootstrapMethodHandle)
                    || bootstrapMethodArguments.length == 0
                    || !(bootstrapMethodArguments[0] instanceof Type samMethodType)) {
                return remapper.mapInvokeDynamicMethodName(name, descriptor);
            }

            var samOwner = Type.getReturnType(descriptor);
            if (samOwner.getSort() != Type.OBJECT) {
                return remapper.mapInvokeDynamicMethodName(name, descriptor);
            }

            return remapper.mapMethodName(samOwner.getInternalName(), name, samMethodType.getDescriptor());
        }

        private static boolean isLambdaMetafactory(Handle bootstrapMethodHandle) {
            if (!LAMBDA_METAFACTORY.equals(bootstrapMethodHandle.getOwner())
                    || bootstrapMethodHandle.getTag() != Opcodes.H_INVOKESTATIC) {
                return false;
            }

            return ("metafactory".equals(bootstrapMethodHandle.getName())
                            && METAFACTORY_DESCRIPTOR.equals(bootstrapMethodHandle.getDesc()))
                    || ("altMetafactory".equals(bootstrapMethodHandle.getName())
                            && ALT_METAFACTORY_DESCRIPTOR.equals(bootstrapMethodHandle.getDesc()));
        }
    }

    private static final class LegacyMinecraftReferenceRemapper extends Remapper {
        private final LegacyForgeRemapper delegate;
        private final Map<String, ClassInfo> classInfos;

        private LegacyMinecraftReferenceRemapper(LegacyForgeRemapper delegate, Map<String, ClassInfo> classInfos) {
            this.delegate = delegate;
            this.classInfos = classInfos;
        }

        @Override
        public String map(String internalName) {
            return delegate.map(internalName);
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            if (isDeclaredMinecraftField(owner, name)) {
                return name;
            }
            return delegate.mapFieldName(owner, name, descriptor);
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            if (isDeclaredMinecraftMethod(owner, name, descriptor)) {
                return name;
            }
            return delegate.mapMethodName(owner, name, descriptor);
        }

        private boolean isDeclaredMinecraftField(String owner, String name) {
            if (!isMinecraftClass(owner)) {
                return false;
            }
            var classInfo = classInfos.get(owner);
            return classInfo != null && classInfo.fields().stream().anyMatch(field -> field.name().equals(name));
        }

        private boolean isDeclaredMinecraftMethod(String owner, String name, String descriptor) {
            if (!isMinecraftClass(owner)) {
                return false;
            }
            var classInfo = classInfos.get(owner);
            return classInfo != null && classInfo.methods().stream()
                    .anyMatch(method -> method.name().equals(name) && method.descriptor().equals(descriptor));
        }
    }

    private static final class LegacyForgeRemapper extends Remapper {
        private final LegacyForgeMappings mappings;

        private LegacyForgeRemapper(LegacyForgeMappings mappings) {
            this.mappings = mappings;
        }

        @Override
        public String map(String internalName) {
            return mappings.classMappings().getOrDefault(internalName, internalName);
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            var mappedName = mappings.fieldMappings().get(new MemberKey(owner, name, null));
            if (mappedName != null) {
                return mappedName;
            }

            var mappedOwner = mappings.classMappings().get(owner);
            if (mappedOwner != null) {
                mappedName = mappings.fieldMappings().get(new MemberKey(mappedOwner, name, null));
                if (mappedName != null) {
                    return mappedName;
                }
            }
            return name;
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            var mappedName = mappings.methodMappings().get(new MemberKey(owner, name, descriptor));
            if (mappedName != null) {
                return mappedName;
            }

            var mappedDescriptor = mapMethodDesc(descriptor);
            mappedName = mappings.methodMappings().get(new MemberKey(owner, name, mappedDescriptor));
            if (mappedName != null) {
                return mappedName;
            }

            var mappedOwner = mappings.classMappings().get(owner);
            if (mappedOwner != null) {
                mappedName = mappings.methodMappings().get(new MemberKey(mappedOwner, name, mappedDescriptor));
                if (mappedName != null) {
                    return mappedName;
                }
            }
            return name;
        }

        private String mapStringConstant(String value) {
            return mappings.stringMappings().getOrDefault(value, value);
        }
    }

    private record LegacyForgeMappings(
            Map<String, String> classMappings,
            Map<MemberKey, String> fieldMappings,
            Map<MemberKey, String> methodMappings,
            Map<String, String> stringMappings) {
        private LegacyForgeMappings(
                Map<String, String> classMappings,
                Map<MemberKey, String> fieldMappings,
                Map<MemberKey, String> methodMappings) {
            this(classMappings, fieldMappings, methodMappings, Map.of());
        }

        LegacyForgeMappings withSrgToMcp(SrgToMcpMappings srgToMcp) {
            var composedFields = new HashMap<MemberKey, String>();
            var composedStringMappings = new HashMap<>(stringMappings);
            for (var entry : fieldMappings.entrySet()) {
                var key = entry.getKey();
                var mappedOwner = classMappings.getOrDefault(key.owner(), key.owner());
                var srgName = entry.getValue();
                var mcpName = srgToMcp.fieldMappings().getOrDefault(new MemberKey(mappedOwner, srgName, null), srgName);
                composedFields.put(key, mcpName);
                composedFields.put(new MemberKey(mappedOwner, key.name(), null), mcpName);
                addUniqueStringMapping(composedStringMappings, srgName, mcpName);
            }

            var classOnlyRemapper = new LegacyForgeRemapper(new LegacyForgeMappings(classMappings, Map.of(), Map.of()));
            var composedMethods = new HashMap<MemberKey, String>();
            for (var entry : methodMappings.entrySet()) {
                var key = entry.getKey();
                var mappedOwner = classMappings.getOrDefault(key.owner(), key.owner());
                var mappedDescriptor = classOnlyRemapper.mapMethodDesc(key.descriptor());
                var srgName = entry.getValue();
                var mcpName = srgToMcp.methodMappings().getOrDefault(new MemberKey(mappedOwner, srgName, mappedDescriptor), srgName);
                composedMethods.put(key, mcpName);
                composedMethods.put(new MemberKey(mappedOwner, key.name(), mappedDescriptor), mcpName);
                addUniqueStringMapping(composedStringMappings, srgName, mcpName);
            }

            composedStringMappings.values().removeIf(value -> value == null);
            return new LegacyForgeMappings(classMappings, composedFields, composedMethods, composedStringMappings);
        }

        LegacyForgeMappings withInheritedMemberMappings(Map<String, ClassInfo> classInfos) {
            var remappedFields = new HashMap<>(fieldMappings);
            var remappedMethods = new HashMap<>(methodMappings);
            var classOnlyRemapper = new LegacyForgeRemapper(new LegacyForgeMappings(classMappings, Map.of(), Map.of()));

            for (var classInfo : classInfos.values()) {
                if (!isMinecraftOrForgeClass(classInfo.name())) {
                    continue;
                }

                for (var fieldMapping : findInheritedFieldMappings(
                        classInfo.superName(),
                        classInfo.interfaces(),
                        classInfos,
                        new HashSet<>()).entrySet()) {
                    remappedFields.put(new MemberKey(classInfo.name(), fieldMapping.getKey(), null), fieldMapping.getValue());
                    putObfuscatedClassMemberMapping(remappedFields, classInfo.name(), fieldMapping.getKey(), null, fieldMapping.getValue());
                }

                for (var method : classInfo.methods()) {
                    if (method.name().startsWith("<")
                            || (method.access() & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) != 0) {
                        continue;
                    }

                    var mappedName = findMappedOverrideName(
                            classInfo.superName(),
                            classInfo.interfaces(),
                            method.name(),
                            method.descriptor(),
                            classInfos,
                            classOnlyRemapper,
                            new HashSet<>());
                    if (mappedName != null && !mappedName.equals(method.name())) {
                        remappedMethods.put(new MemberKey(classInfo.name(), method.name(), method.descriptor()), mappedName);
                        putObfuscatedClassMemberMapping(remappedMethods, classInfo.name(), method.name(), method.descriptor(), mappedName);
                    }
                }

                for (var methodMapping : findInheritedMethodMappings(
                        classInfo.superName(),
                        classInfo.interfaces(),
                        classInfos,
                        classOnlyRemapper,
                        new HashSet<>()).entrySet()) {
                    remappedMethods.put(new MemberKey(classInfo.name(), methodMapping.getKey().name(), methodMapping.getKey().descriptor()), methodMapping.getValue());
                    putObfuscatedClassMemberMapping(
                            remappedMethods,
                            classInfo.name(),
                            methodMapping.getKey().name(),
                            methodMapping.getKey().descriptor(),
                            methodMapping.getValue());
                }
            }

            return new LegacyForgeMappings(classMappings, remappedFields, remappedMethods, stringMappings);
        }

        private static void addUniqueStringMapping(Map<String, String> stringMappings, String sourceName, String mappedName) {
            if (sourceName.equals(mappedName)) {
                return;
            }

            var existing = stringMappings.putIfAbsent(sourceName, mappedName);
            if (existing != null && !existing.equals(mappedName)) {
                stringMappings.put(sourceName, null);
            }
        }

        private void putObfuscatedClassMemberMapping(
                Map<MemberKey, String> mappings,
                String mappedOwner,
                String name,
                String descriptor,
                String mappedName) {
            for (var entry : classMappings.entrySet()) {
                if (entry.getValue().equals(mappedOwner)) {
                    mappings.put(new MemberKey(entry.getKey(), name, descriptor), mappedName);
                    return;
                }
            }
        }

        private boolean isMinecraftOrForgeClass(String name) {
            return name.startsWith("net/minecraft/") || shouldRemapClass(name);
        }

        private Map<String, String> findInheritedFieldMappings(
                String superName,
                List<String> interfaces,
                Map<String, ClassInfo> classInfos,
                HashSet<String> visited) {
            var inheritedFields = new HashMap<String, String>();
            addInheritedFieldMappings(superName, classInfos, visited, inheritedFields);
            for (var interfaceName : interfaces) {
                addInheritedFieldMappings(interfaceName, classInfos, visited, inheritedFields);
            }
            return inheritedFields;
        }

        private void addInheritedFieldMappings(
                String owner,
                Map<String, ClassInfo> classInfos,
                HashSet<String> visited,
                Map<String, String> inheritedFields) {
            if (owner == null || !visited.add(owner)) {
                return;
            }

            for (var entry : fieldMappings.entrySet()) {
                if (entry.getKey().owner().equals(owner)) {
                    inheritedFields.putIfAbsent(entry.getKey().name(), entry.getValue());
                }
            }

            var classInfo = classInfos.get(owner);
            if (classInfo == null) {
                classInfo = classInfos.get(classMappings.get(owner));
            }
            if (classInfo == null) {
                return;
            }

            for (var field : classInfo.fields()) {
                if ((field.access() & Opcodes.ACC_PRIVATE) != 0) {
                    inheritedFields.remove(field.name());
                }
            }

            addInheritedFieldMappings(classInfo.superName(), classInfos, visited, inheritedFields);
            for (var interfaceName : classInfo.interfaces()) {
                addInheritedFieldMappings(interfaceName, classInfos, visited, inheritedFields);
            }
        }

        private Map<MemberKey, String> findInheritedMethodMappings(
                String superName,
                List<String> interfaces,
                Map<String, ClassInfo> classInfos,
                Remapper classOnlyRemapper,
                HashSet<String> visited) {
            var inheritedMethods = new HashMap<MemberKey, String>();
            addInheritedMethodMappings(superName, classInfos, classOnlyRemapper, visited, inheritedMethods);
            for (var interfaceName : interfaces) {
                addInheritedMethodMappings(interfaceName, classInfos, classOnlyRemapper, visited, inheritedMethods);
            }
            return inheritedMethods;
        }

        private void addInheritedMethodMappings(
                String owner,
                Map<String, ClassInfo> classInfos,
                Remapper classOnlyRemapper,
                HashSet<String> visited,
                Map<MemberKey, String> inheritedMethods) {
            if (owner == null || !visited.add(owner)) {
                return;
            }

            for (var entry : methodMappings.entrySet()) {
                var key = entry.getKey();
                if (key.owner().equals(owner)) {
                    inheritedMethods.putIfAbsent(new MemberKey(null, key.name(), key.descriptor()), entry.getValue());
                    continue;
                }

                var mappedOwner = classMappings.getOrDefault(owner, owner);
                if (key.owner().equals(mappedOwner)) {
                    var obfuscatedDescriptor = classOnlyRemapper.mapMethodDesc(key.descriptor());
                    inheritedMethods.putIfAbsent(new MemberKey(null, key.name(), obfuscatedDescriptor), entry.getValue());
                }
            }

            var classInfo = classInfos.get(owner);
            if (classInfo == null) {
                classInfo = classInfos.get(classMappings.get(owner));
            }
            if (classInfo == null) {
                return;
            }

            for (var method : classInfo.methods()) {
                if ((method.access() & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) != 0) {
                    inheritedMethods.remove(new MemberKey(null, method.name(), method.descriptor()));
                }
            }

            addInheritedMethodMappings(classInfo.superName(), classInfos, classOnlyRemapper, visited, inheritedMethods);
            for (var interfaceName : classInfo.interfaces()) {
                addInheritedMethodMappings(interfaceName, classInfos, classOnlyRemapper, visited, inheritedMethods);
            }
        }

        private String findMappedOverrideName(
                String superName,
                List<String> interfaces,
                String name,
                String descriptor,
                Map<String, ClassInfo> classInfos,
                Remapper classOnlyRemapper,
                HashSet<String> visited) {
            var mappedName = findMappedOverrideName(superName, name, descriptor, classInfos, classOnlyRemapper, visited);
            if (mappedName != null) {
                return mappedName;
            }

            for (var interfaceName : interfaces) {
                mappedName = findMappedOverrideName(interfaceName, name, descriptor, classInfos, classOnlyRemapper, visited);
                if (mappedName != null) {
                    return mappedName;
                }
            }
            return null;
        }

        private String findMappedOverrideName(
                String owner,
                String name,
                String descriptor,
                Map<String, ClassInfo> classInfos,
                Remapper classOnlyRemapper,
                HashSet<String> visited) {
            if (owner == null || !visited.add(owner)) {
                return null;
            }

            var mappedName = mappedMethodName(owner, name, descriptor, classOnlyRemapper);
            if (mappedName != null) {
                return mappedName;
            }

            var classInfo = classInfos.get(owner);
            if (classInfo == null) {
                classInfo = classInfos.get(classMappings.get(owner));
            }
            if (classInfo == null) {
                return null;
            }

            return findMappedOverrideName(
                    classInfo.superName(),
                    classInfo.interfaces(),
                    name,
                    descriptor,
                    classInfos,
                    classOnlyRemapper,
                    visited);
        }

        private String mappedMethodName(String owner, String name, String descriptor, Remapper classOnlyRemapper) {
            var mappedName = methodMappings.get(new MemberKey(owner, name, descriptor));
            if (mappedName != null) {
                return mappedName;
            }

            var mappedOwner = classMappings.getOrDefault(owner, owner);
            var mappedDescriptor = classOnlyRemapper.mapMethodDesc(descriptor);
            mappedName = methodMappings.get(new MemberKey(mappedOwner, name, mappedDescriptor));
            if (mappedName != null) {
                return mappedName;
            }

            mappedName = methodMappings.get(new MemberKey(owner, name, mappedDescriptor));
            if (mappedName != null) {
                return mappedName;
            }

            return methodMappings.get(new MemberKey(mappedOwner, name, descriptor));
        }
    }

    private record SrgToMcpMappings(Map<MemberKey, String> fieldMappings, Map<MemberKey, String> methodMappings) {}

    private record Member(String owner, String name) {}

    private record MemberKey(String owner, String name, String descriptor) {}

    private record ClassInfo(String name, String superName, List<String> interfaces, List<FieldInfo> fields, List<MethodInfo> methods) {}

    private record FieldInfo(int access, String name, String descriptor) {}

    private record MethodInfo(int access, String name, String descriptor) {}
}
