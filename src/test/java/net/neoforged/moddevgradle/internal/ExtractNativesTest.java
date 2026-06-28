package net.neoforged.moddevgradle.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExtractNativesTest {
    @TempDir
    Path tempDir;

    @Test
    void extractsNativeJarsWithDuplicateMetadataEntries() throws IOException {
        var firstJar = createJar("first.jar", Map.of(
                "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n",
                "linux/libfirst.so", "first"));
        var secondJar = createJar("second.jar", Map.of(
                "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n",
                "linux/libsecond.so", "second"));

        var project = ProjectBuilder.builder().build();
        var task = project.getTasks().register("extractNatives", ExtractNatives.class).get();
        task.getEnabledForRun().set(true);
        task.getNativeLibraries().from(firstJar, secondJar);
        task.getOutputDirectory().set(tempDir.resolve("natives").toFile());

        task.extract();

        assertThat(tempDir.resolve("natives/linux/libfirst.so")).hasContent("first");
        assertThat(tempDir.resolve("natives/linux/libsecond.so")).hasContent("second");
    }

    @Test
    void renamesLwjgl2Arm64MacosNativeToLegacyJniLibName() throws IOException {
        var lwjgl2Arm64Natives = createJar("lwjgl-platform-2.9.4-nightly-20150209-mmachina.2-natives-osx.jar", Map.of(
                "liblwjgl.dylib", "arm64 lwjgl",
                "openal.dylib", "arm64 openal"));

        var project = ProjectBuilder.builder().build();
        var task = project.getTasks().register("extractNatives", ExtractNatives.class).get();
        task.getEnabledForRun().set(true);
        task.getNativeLibraries().from(lwjgl2Arm64Natives);
        task.getOutputDirectory().set(tempDir.resolve("natives").toFile());

        task.extract();

        assertThat(tempDir.resolve("natives/liblwjgl.jnilib")).hasContent("arm64 lwjgl");
        assertThat(tempDir.resolve("natives/liblwjgl.dylib")).doesNotExist();
        assertThat(tempDir.resolve("natives/openal.dylib")).hasContent("arm64 openal");
    }

    @Test
    void letsLwjgl2Arm64MacosNativeOverrideLegacyOsxNative() throws IOException {
        var legacyOsxNatives = createJar("lwjgl-platform-2.9.1-natives-osx.jar", Map.of(
                "liblwjgl.jnilib", "x64 lwjgl",
                "openal.dylib", "x64 openal"));
        var lwjgl2Arm64Natives = createJar("lwjgl-platform-2.9.4-nightly-20150209-mmachina.2-natives-osx.jar", Map.of(
                "liblwjgl.dylib", "arm64 lwjgl",
                "openal.dylib", "arm64 openal"));

        var project = ProjectBuilder.builder().build();
        var task = project.getTasks().register("extractNatives", ExtractNatives.class).get();
        task.getEnabledForRun().set(true);
        task.getNativeLibraries().from(legacyOsxNatives, lwjgl2Arm64Natives);
        task.getOutputDirectory().set(tempDir.resolve("natives").toFile());

        task.extract();

        assertThat(tempDir.resolve("natives/liblwjgl.jnilib")).hasContent("arm64 lwjgl");
        assertThat(tempDir.resolve("natives/openal.dylib")).hasContent("arm64 openal");
    }

    private Path createJar(String fileName, Map<String, String> entries) throws IOException {
        var jar = tempDir.resolve(fileName);
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return jar;
    }
}
