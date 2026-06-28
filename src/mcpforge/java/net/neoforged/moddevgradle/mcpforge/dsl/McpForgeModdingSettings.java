package net.neoforged.moddevgradle.mcpforge.dsl;

import javax.inject.Inject;
import net.neoforged.moddevgradle.legacyforge.dsl.LegacyForgeModdingSettings;
import org.gradle.api.Project;
import org.jetbrains.annotations.Nullable;

public abstract class McpForgeModdingSettings extends LegacyForgeModdingSettings {
    private String mcpMappings;

    @Inject
    public McpForgeModdingSettings(Project project) {
        super(project);
    }

    public @Nullable String getMcpMappings() {
        return mcpMappings;
    }

    public void setMcpMappings(String mcpMappings) {
        this.mcpMappings = mcpMappings;
    }
}
