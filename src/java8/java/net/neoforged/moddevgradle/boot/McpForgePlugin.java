package net.neoforged.moddevgradle.boot;

import org.gradle.api.Project;

/**
 * Boot trampoline for the {@code net.neoforged.moddev.mcpforge} plugin (the isolated 1.12.2/MCP toolchain).
 * Mirrors {@link LegacyForgeModDevPlugin}. Kept in the java8 source set so it can be loaded by the
 * bootstrap classloader before the main plugin classpath is wired up.
 */
public class McpForgePlugin extends TrampolinePlugin<Project> {
    public McpForgePlugin() {
        super("net.neoforged.moddevgradle.mcpforge.internal.McpForgeModDevPlugin");
    }
}
