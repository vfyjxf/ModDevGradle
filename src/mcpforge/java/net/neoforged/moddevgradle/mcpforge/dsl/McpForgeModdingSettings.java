package net.neoforged.moddevgradle.mcpforge.dsl;

import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;
import net.neoforged.moddevgradle.internal.utils.ExtensionUtils;
import org.gradle.api.Project;
import org.gradle.api.tasks.SourceSet;
import org.jetbrains.annotations.Nullable;

/**
 * Settings for the mcpforge {@code enable {}} block. This is a standalone type (it deliberately does NOT extend
 * {@code LegacyForgeModdingSettings}) so that the no-recompile option cannot even be expressed: the 1.12.2/MCP
 * toolchain only supports the decompile+recompile path, and there is no {@code disableRecompilation} setter here.
 */
public abstract class McpForgeModdingSettings {
    @Nullable
    private String neoForgeVersion;

    @Nullable
    private String forgeVersion;

    @Nullable
    private String mcpVersion;

    @Nullable
    private String mcpMappings;

    private Set<SourceSet> enabledSourceSets = new HashSet<>();

    private boolean obfuscateJar = true;

    @Inject
    public McpForgeModdingSettings(Project project) {
        // By default, enable modding deps only for the main source set
        var sourceSets = ExtensionUtils.getSourceSets(project);
        var mainSourceSet = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        enabledSourceSets.add(mainSourceSet);
    }

    public @Nullable String getNeoForgeVersion() {
        return neoForgeVersion;
    }

    public @Nullable String getForgeVersion() {
        return forgeVersion;
    }

    public @Nullable String getMcpVersion() {
        return mcpVersion;
    }

    /**
     * NeoForge version number. You have to set either this, {@link #setForgeVersion} or {@link #setMcpVersion}.
     */
    public void setNeoForgeVersion(String version) {
        this.neoForgeVersion = version;
    }

    /**
     * Minecraft Forge version. You have to set either this, {@link #setNeoForgeVersion} or {@link #setMcpVersion}.
     */
    public void setForgeVersion(String version) {
        this.forgeVersion = version;
    }

    /**
     * Set this to a version of <a href="https://maven.neoforged.net/#/releases/de/oceanlabs/mcp/mcp">MCP</a>
     * to compile against Vanilla artifacts that have no Forge code added.
     */
    public void setMcpVersion(String version) {
        this.mcpVersion = version;
    }

    /**
     * Gradle dependency notation of the legacy MCP mapping zip, e.g.
     * {@code de.oceanlabs.mcp:mcp_stable:39-1.12@zip}. Required for 1.12.2 Forge/MCP builds.
     */
    public @Nullable String getMcpMappings() {
        return mcpMappings;
    }

    public void setMcpMappings(String mcpMappings) {
        this.mcpMappings = mcpMappings;
    }

    /**
     * Contains the list of source sets for which access to Minecraft classes should be configured.
     * Defaults to the main source set, but can also be set to an empty list.
     */
    public Set<SourceSet> getEnabledSourceSets() {
        return enabledSourceSets;
    }

    public void setEnabledSourceSets(Set<SourceSet> enabledSourceSets) {
        this.enabledSourceSets = enabledSourceSets;
    }

    /**
     * {@return true if the default reobfuscation task should be created}
     */
    public boolean isObfuscateJar() {
        return obfuscateJar;
    }

    public void setObfuscateJar(boolean obfuscateJar) {
        this.obfuscateJar = obfuscateJar;
    }
}
