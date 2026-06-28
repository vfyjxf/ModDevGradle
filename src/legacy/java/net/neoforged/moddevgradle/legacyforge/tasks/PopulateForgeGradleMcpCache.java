package net.neoforged.moddevgradle.legacyforge.tasks;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.Optional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Populates the ForgeGradle-2.x MCP cache directory with the MCP data that ModDevGradle produced, so that legacy
 * tooling and build scripts that hardcode the ForgeGradle-2 cache layout continue to find the mappings.
 * <p>
 * ForgeGradle-2 stores MCP data under {@code ~/.gradle/caches/minecraft/de/oceanlabs/mcp/<name>/<version>/} (the CSV
 * files) and the SRG mapping files under {@code .../<name>/<version>/<minecraftVersion>/srgs/}. ModDevGradle instead
 * exposes the same data as build artifacts (requested via {@code createMinecraftArtifacts}); this task mirrors them
 * into the ForgeGradle-2 locations for compatibility.
 * <p>
 * This only covers the data ModDevGradle has available: the {@code methods.csv}/{@code fields.csv}/{@code params.csv}
 * (from the CSV mapping zip), {@code srg-mcp.srg} (= SRG&#45;&gt;MCP) and {@code mcp-srg.srg} (= MCP&#45;&gt;SRG). The
 * notch-targeted SRG files ({@code notch-srg.srg} etc.) that ForgeGradle-2 also generates are intentionally omitted,
 * as production-name obfuscation for 1.12.2 targets SRG (not notch) in this toolchain.
 */
public abstract class PopulateForgeGradleMcpCache extends DefaultTask {

    /** Gradle dependency notation of the legacy MCP mapping zip, e.g. {@code de.oceanlabs.mcp:mcp_stable:39-1.12@zip}. */
    @Input
    public abstract Property<String> getMcpMappings();

    /** The Minecraft version, e.g. {@code 1.12.2}. */
    @Input
    public abstract Property<String> getMinecraftVersion();

    /**
     * The ForgeGradle-2 cache base directory to populate, i.e.
     * {@code <gradleUserHome>/caches/minecraft/de/oceanlabs/mcp/<name>/<version>}. Resolved at configuration time so
     * the task remains configuration-cache compatible.
     */
    @Input
    public abstract Property<String> getCacheBaseDirectory();

    /** The MCP CSV mapping zip (SRG&#45;&gt;MCP CSVs), as produced by NFRT's {@code csvMapping} result. */
    @InputFile
    public abstract RegularFileProperty getCsvMappings();

    /** The SRG&#45;&gt;MCP SRG mapping file (NFRT {@code intermediaryToNamedMapping}), mirrors {@code srgs/srg-mcp.srg}. */
    @InputFile
    public abstract RegularFileProperty getSrgToMcpMappings();

    /** The MCP&#45;&gt;SRG mapping file (NFRT {@code namedToIntermediaryMapping}), mirrors {@code srgs/mcp-srg.srg}. */
    @InputFile
    public abstract RegularFileProperty getMcpToSrgMappings();

    /** The notch&#45;&gt;SRG mapping file (NFRT {@code notchToIntermediaryMapping}), mirrors {@code srgs/notch-srg.srg}. */
    @InputFile
    @org.gradle.api.tasks.Optional
    public abstract RegularFileProperty getNotchToSrgMappings();

    @TaskAction
    public void populate() throws IOException {
        var minecraftVersion = getMinecraftVersion().get();
        var cacheBase = Path.of(getCacheBaseDirectory().get());

        // CSV files live directly under the version directory
        extractCsvs(getCsvMappings().get().getAsFile().toPath(), cacheBase);

        // SRG mapping files live under <version>/<minecraftVersion>/srgs/
        var srgsDir = cacheBase.resolve(minecraftVersion).resolve("srgs");
        Files.createDirectories(srgsDir);
        Files.copy(getSrgToMcpMappings().get().getAsFile().toPath(), srgsDir.resolve("srg-mcp.srg"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(getMcpToSrgMappings().get().getAsFile().toPath(), srgsDir.resolve("mcp-srg.srg"), StandardCopyOption.REPLACE_EXISTING);
        if (getNotchToSrgMappings().isPresent()) {
            Files.copy(getNotchToSrgMappings().get().getAsFile().toPath(), srgsDir.resolve("notch-srg.srg"), StandardCopyOption.REPLACE_EXISTING);
        }

        getLogger().lifecycle("Populated ForgeGradle-2 MCP cache at {}", cacheBase);
    }

    private static void extractCsvs(Path mappingsZip, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        try (var zip = new ZipFile(mappingsZip.toFile())) {
            for (String csv : new String[]{"methods.csv", "fields.csv", "params.csv"}) {
                var entry = zip.getEntry(csv);
                if (entry == null) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, targetDir.resolve(csv), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
