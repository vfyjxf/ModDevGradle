package net.neoforged.moddevgradle.internal.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileUtilsTest {
    @TempDir
    Path tempDir;

    @Test
    void stripsJarSignatureMetadataAndKeepsManifestMainAttributes() throws Exception {
        var jar = tempDir.resolve("signed.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Tweak-Class", "net.minecraftforge.fml.common.launcher.FMLTweaker");
        manifest.getEntries().computeIfAbsent("net/minecraftforge/fml/relauncher/libraries/LibraryManager.class", ignored -> new Attributes())
                .putValue("SHA-256-Digest", "invalid");

        try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            writeEntry(output, "META-INF/FORGE.SF", "signature file");
            writeEntry(output, "META-INF/FORGE.DSA", "signature block");
            writeEntry(output, "net/minecraftforge/fml/relauncher/libraries/LibraryManager.class", "patched class");
        }

        FileUtils.stripJarSignatures(jar);

        try (var result = new JarFile(jar.toFile())) {
            assertThat(result.getEntry("META-INF/FORGE.SF")).isNull();
            assertThat(result.getEntry("META-INF/FORGE.DSA")).isNull();
            assertThat(result.getEntry("net/minecraftforge/fml/relauncher/libraries/LibraryManager.class")).isNotNull();
            assertThat(result.getManifest().getMainAttributes().getValue("Tweak-Class"))
                    .isEqualTo("net.minecraftforge.fml.common.launcher.FMLTweaker");
            assertThat(result.getManifest().getEntries()).isEmpty();
        }
    }

    @Test
    void removesSelectedJarEntriesAndKeepsManifest() throws Exception {
        var jar = tempDir.resolve("patched.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Tweak-Class", "net.minecraftforge.fml.common.launcher.FMLTweaker");

        try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            writeEntry(output, "binpatches.pack.lzma", "runtime patches");
            writeEntry(output, "net/minecraft/client/Minecraft.class", "patched class");
        }

        FileUtils.removeJarEntries(jar, java.util.Set.of("binpatches.pack.lzma"));

        try (var result = new JarFile(jar.toFile())) {
            assertThat(result.getEntry("binpatches.pack.lzma")).isNull();
            assertThat(result.getEntry("net/minecraft/client/Minecraft.class")).isNotNull();
            assertThat(result.getManifest().getMainAttributes().getValue("Tweak-Class"))
                    .isEqualTo("net.minecraftforge.fml.common.launcher.FMLTweaker");
        }
    }

    private static void writeEntry(JarOutputStream output, String name, String content) throws Exception {
        output.putNextEntry(new JarEntry(name));
        output.write(content.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }
}
