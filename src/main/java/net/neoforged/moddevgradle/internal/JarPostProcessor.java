package net.neoforged.moddevgradle.internal;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Post-processes a Minecraft jar after NFRT generation, for legacy versions that need additional transformation
 * (e.g. 1.12.2 Forge deobfuscation data remapping).
 *
 * <p>Registered via {@code CreateMinecraftArtifacts.getJarPostProcessors()}; the MCP plugin registers its
 * implementation, the default is an empty list.
 */
@ApiStatus.Internal
@FunctionalInterface
public interface JarPostProcessor {
    /**
     * Process the generated jar (may be modified in place).
     *
     * @param srgToMcpMappings the SRG→MCP mapping file, or null if not applicable
     */
    void process(Path jar, @Nullable Path srgToMcpMappings) throws IOException;
}
