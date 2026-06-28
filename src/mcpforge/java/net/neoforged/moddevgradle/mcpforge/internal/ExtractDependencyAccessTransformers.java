package net.neoforged.moddevgradle.mcpforge.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

/**
 * Extracts Access Transformer {@code .cfg} files declared via the {@code FMLAT} manifest attribute in dependency jars.
 *
 * <p>FML's contract: a mod/coremod jar may carry {@code FMLAT: <space-separated cfg basenames>}, each at
 * {@code META-INF/<name>} inside the jar. MDG has no runtime AT class-transformer (it runs the recompiled MC jar
 * directly), so dependency ATs must be applied at build time — the extracted cfgs are fed into the
 * {@code accessTransformers} DataFileCollection so NFRT bakes them in via {@code --access-transformer}.
 *
 * <p>AT files use SRG member names and are extracted verbatim (only CRLF→LF normalization); NFRT renames
 * SRG→MCP during recompilation.
 */
public abstract class ExtractDependencyAccessTransformers extends DefaultTask {

    private static final Attributes.Name FMLAT = new Attributes.Name("FMLAT");

    /** Dependency jars to scan for {@code FMLAT} declarations (typically compileClasspath + runtimeClasspath). */
    @InputFiles
    public abstract ConfigurableFileCollection getDependencies();

    /** Directory where extracted AT cfgs are written: one file per (jar, at) pair. */
    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @TaskAction
    public void extract() throws IOException {
        var outDir = getOutputDirectory().get().getAsFile().toPath();
        Files.createDirectories(outDir);

        for (var file : getDependencies().getFiles()) {
            if (file == null || !file.isFile() || !file.getName().toLowerCase().endsWith(".jar")) {
                continue;
            }
            try (var jar = new JarFile(file, false)) {
                var manifest = jar.getManifest();
                if (manifest == null) {
                    continue;
                }
                var atNames = manifest.getMainAttributes().getValue(FMLAT);
                if (atNames == null || atNames.isBlank()) {
                    continue;
                }
                var jarBase = stripExtension(file.getName());
                for (var rawName : atNames.split(" ")) {
                    var atName = rawName.trim();
                    if (atName.isEmpty()) {
                        continue;
                    }
                    var entry = jar.getEntry("META-INF/" + atName);
                    if (entry == null) {
                        getLogger().warn("Dependency AT '{}!META-INF/{}' not found, skipping.", file.getName(), atName);
                        continue;
                    }
                    String content;
                    try (InputStream in = jar.getInputStream(entry)) {
                        content = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\r\n", "\n");
                    }
                    var outFile = outDir.resolve(jarBase + "-" + sanitize(atName) + ".cfg");
                    Files.writeString(outFile, content, StandardCharsets.UTF_8);
                    getLogger().lifecycle("Extracted dependency AT '{}' from {}", atName, file.getName());
                }
            } catch (IOException ignored) {
                // Not a readable jar — skip.
            }
        }
    }

    private static String stripExtension(String name) {
        var i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
