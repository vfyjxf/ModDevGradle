package net.neoforged.moddevgradle.legacyforge.tasks;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipFile;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;

/**
 * Populates the ForgeGradle-2.x MCP cache directory with the MCP data ModDevGradle produced, so legacy tooling and
 * 1.12.2 mods that hardcode the ForgeGradle-2 cache layout (and read it at runtime via {@code GradleStart} system
 * properties) keep finding the mappings.
 * <p>
 * ForgeGradle-2 stores MCP data under {@code ~/.gradle/caches/minecraft/de/oceanlabs/mcp/<name>/<version>/}: the CSV
 * files directly there, and the SRG maps under {@code .../<version>/<minecraftVersion>/srgs/}. This task mirrors the
 * NFRT-produced artifacts into that layout. The {@code srg-mcp}/{@code mcp-srg}/{@code notch-srg} maps are copied
 * verbatim; when a {@code notch}-&gt;SRG map is available the remaining FG-2 maps ({@code srg-notch}, {@code notch-mcp},
 * {@code mcp-notch}) are derived by inverting/composing the SRG-format text, so every {@code GradleStart.srg.*}
 * property ForgeGradle-2's {@code GradleStartCommon} would have set resolves to a real file.
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

    /** The MCP CSV mapping zip (methods/fields/params), as produced by NFRT's {@code csvMapping} result. */
    @InputFile
    public abstract RegularFileProperty getCsvMappings();

    /** The SRG-&gt;MCP SRG mapping file (NFRT {@code intermediaryToNamedMapping}), mirrors {@code srgs/srg-mcp.srg}. */
    @InputFile
    public abstract RegularFileProperty getSrgToMcpMappings();

    /** The MCP-&gt;SRG mapping file (NFRT {@code namedToIntermediaryMapping}), mirrors {@code srgs/mcp-srg.srg}. */
    @InputFile
    public abstract RegularFileProperty getMcpToSrgMappings();

    /** The notch-&gt;SRG mapping file (NFRT {@code notchToIntermediaryMapping}); when present, {@code srgs/notch-srg.srg} and the derived maps are written. */
    @InputFile
    @Optional
    public abstract RegularFileProperty getNotchToSrgMappings();

    @TaskAction
    public void populate() throws IOException {
        var minecraftVersion = getMinecraftVersion().get();
        var cacheBase = Path.of(getCacheBaseDirectory().get());

        extractCsvs(getCsvMappings().get().getAsFile().toPath(), cacheBase);

        var srgsDir = cacheBase.resolve(minecraftVersion).resolve("srgs");
        Files.createDirectories(srgsDir);

        var srgToMcp = getSrgToMcpMappings().get().getAsFile().toPath();
        var mcpToSrg = getMcpToSrgMappings().get().getAsFile().toPath();

        copyTo(srgToMcp, srgsDir.resolve("srg-mcp.srg"));
        copyTo(mcpToSrg, srgsDir.resolve("mcp-srg.srg"));

        if (getNotchToSrgMappings().isPresent()) {
            var notchToSrg = getNotchToSrgMappings().get().getAsFile().toPath();
            copyTo(notchToSrg, srgsDir.resolve("notch-srg.srg"));

            var notchSrg = readSrg(notchToSrg);
            var srgMcp = readSrg(srgToMcp);
            var composed = compose(notchSrg, srgMcp);

            writeSrg(notchSrg.inverse(), srgsDir.resolve("srg-notch.srg"));
            writeSrg(composed, srgsDir.resolve("notch-mcp.srg"));
            writeSrg(composed.inverse(), srgsDir.resolve("mcp-notch.srg"));
        }

        getLogger().lifecycle("Populated ForgeGradle-2 MCP cache at {}", cacheBase);
    }

    private static void copyTo(Path src, Path dst) throws IOException {
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void extractCsvs(Path mappingsZip, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        try (var zip = new ZipFile(mappingsZip.toFile())) {
            for (String csv : new String[]{"methods.csv", "fields.csv", "params.csv"}) {
                var entry = zip.getEntry(csv);
                if (entry == null) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, targetDir.resolve(csv), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static SrgMap readSrg(Path file) throws IOException {
        var map = new SrgMap();
        try (var reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                // CL:/FD: carry one pair (left, right); MD: carries (leftOwner/leftName leftDesc, rightOwner/rightName rightDesc).
                // Splitting with a 4-way limit leaves the right side of MD: (name + desc) intact as a single token.
                var parts = line.split("\\s+", 4);
                switch (parts[0]) {
                    case "CL:", "FD:" -> {
                        if (parts.length >= 3) map.put(parts[0], parts[1], parts[2]);
                    }
                    case "MD:" -> {
                        if (parts.length >= 4) map.put("MD:", parts[1] + " " + parts[2], parts[3]);
                    }
                }
            }
        }
        return map;
    }

    private static void writeSrg(SrgMap map, Path dst) throws IOException {
        Files.createDirectories(dst.getParent());
        try (var writer = Files.newBufferedWriter(dst)) {
            for (var entry : map.entries()) {
                writer.write(entry.tag() + " " + entry.left() + " " + entry.right() + "\n");
            }
        }
    }

    /** Compose two maps: for each left→mid in {@code first}, emit left→(mid looked up in {@code second}). */
    private static SrgMap compose(SrgMap first, SrgMap second) {
        var result = new SrgMap();
        for (var entry : first.entries()) {
            var target = second.get(entry.right());
            result.put(entry.tag(), entry.left(), target != null ? target : entry.right());
        }
        return result;
    }

    private static final class SrgMap {
        private final Map<String, Entry> byLeft = new LinkedHashMap<>();

        void put(String tag, String left, String right) {
            byLeft.put(left, new Entry(tag, left, right));
        }
        String get(String left) {
            var e = byLeft.get(left);
            return e != null ? e.right() : null;
        }
        SrgMap inverse() {
            var inv = new SrgMap();
            for (var e : byLeft.values()) inv.put(e.tag(), e.right(), e.left());
            return inv;
        }
        java.util.Collection<Entry> entries() { return byLeft.values(); }

        private record Entry(String tag, String left, String right) {}
    }
}
