package net.neoforged.moddevgradle.internal;

import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.dsl.DependencyFactory;
import org.jetbrains.annotations.ApiStatus;

/**
 * SPI hooks for MCP-based legacy Minecraft versions (1.12.2 up to 1.16.5).
 *
 * <p>The main workflow looks up a registered instance via the {@code __mcpHooks} project extension. The
 * {@code mcpforge} plugin registers a real implementation (e.g. LWJGL2 Apple Silicon native replacement); when no
 * MCP plugin is active, {@link #NOOP} is used and these calls are no-ops.
 */
@ApiStatus.Internal
public interface McpToolchainHooks {
    McpToolchainHooks NOOP = new McpToolchainHooks() {};

    String EXTENSION_NAME = "__mcpHooks";

    /** Configures runtime native libraries for legacy versions (e.g. LWJGL2 Apple Silicon replacement). */
    default void configureRuntimeNatives(Configuration configuration, DependencyFactory dependencyFactory, String minecraftVersion) {}

    /** Configures native library dependencies for legacy versions (natives extraction). */
    default void configureNativeLibraries(Configuration nativeLibraries, DependencyFactory dependencyFactory, String minecraftVersion) {}

    /**
     * Returns {@code true} if the legacy Apple LWJGL2 native replacement is active for the given version on the
     * current platform. When active, {@code -XstartOnFirstThread} should NOT be added on macOS (the replacement
     * does not require it). Defaults to {@code false} (modern LWJGL3 behavior — add the flag on macOS).
     */
    default boolean usesLegacyAppleLwjgl2(String minecraftVersion) {
        return false;
    }

    /**
     * Returns {@code true} if the legacy Forge splash screen (SplashProgress) should be disabled on macOS for the
     * given version (it is buggy/crashes on Mac for 1.12.2).
     */
    default boolean needsLegacyForgeSplashDisabled(String minecraftVersion) {
        return false;
    }

    /** Retrieves the hooks registered on the project, or {@link #NOOP} if none. */
    static McpToolchainHooks get(org.gradle.api.Project project) {
        var hooks = project.getExtensions().findByName(EXTENSION_NAME);
        return hooks instanceof McpToolchainHooks mcp ? mcp : NOOP;
    }
}
