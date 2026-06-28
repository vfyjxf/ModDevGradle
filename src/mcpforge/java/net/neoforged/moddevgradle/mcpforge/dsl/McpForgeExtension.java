package net.neoforged.moddevgradle.mcpforge.dsl;

import javax.inject.Inject;
import net.neoforged.moddevgradle.dsl.DataFileCollection;
import net.neoforged.moddevgradle.dsl.ModDevExtension;
import net.neoforged.moddevgradle.internal.ModDevArtifactsWorkflow;
import net.neoforged.moddevgradle.mcpforge.dsl.McpForgeModdingSettings;
import net.neoforged.moddevgradle.mcpforge.internal.McpForgeModDevPlugin;
import org.gradle.api.Action;
import org.gradle.api.InvalidUserCodeException;
import org.gradle.api.Project;
import org.gradle.api.provider.ListProperty;

/**
 * The {@code legacyForge} extension for the mcpforge plugin, owning its {@link #enable(Action)} wiring.
 * {@link LegacyForgeModdingSettings} is reused as-is.
 */
public abstract class McpForgeExtension extends ModDevExtension {
    private final Project project;

    @Inject
    public McpForgeExtension(Project project,
            DataFileCollection accessTransformers,
            DataFileCollection interfaceInjectionData) {
        super(project, accessTransformers, interfaceInjectionData);
        this.project = project;
    }

    /** Coordinates of jars that ARE the Mixin provider (exempt from bundled-spongepowered stripping). */
    public abstract ListProperty<String> getMixinProviders();

    /** Declares a jar as the Mixin provider (e.g. {@code mixinProvider 'zone.rong:mixinbooter:10.7'}). */
    public void mixinProvider(String notation) {
        getMixinProviders().add(notation);
    }

    /** Shorthand for {@code enable { forgeVersion = '...' }. */
    public void setVersion(String version) {
        enable(settings -> settings.setForgeVersion(version));
    }

    /** Shorthand for {@code enable { mcpVersion = '...' }. */
    public void setMcpVersion(String version) {
        enable(settings -> settings.setMcpVersion(version));
    }

    /** After enabling, the MCP version picked. Throws if not enabled. */
    public String getMcpVersion() {
        var dependencies = ModDevArtifactsWorkflow.get(project).dependencies();
        if (dependencies.neoFormDependency() == null) {
            throw new InvalidUserCodeException("You cannot retrieve the MCP version without setting it first.");
        }
        return dependencies.neoFormDependency().getVersion();
    }

    public void enable(Action<McpForgeModdingSettings> customizer) {
        var plugin = project.getPlugins().getPlugin(McpForgeModDevPlugin.class);

        var settings = project.getObjects().newInstance(McpForgeModdingSettings.class);
        customizer.execute(settings);

        plugin.enable(project, settings, this);
    }
}
