package net.neoforged.moddevgradle.mcpforge.internal;

import java.io.IOException;
import javax.inject.Inject;

import net.neoforged.moddevgradle.internal.utils.FileUtils;
import net.neoforged.moddevgradle.legacyforge.tasks.RemapOperation;
import org.gradle.api.artifacts.transform.InputArtifact;
import org.gradle.api.artifacts.transform.InputArtifactDependencies;
import org.gradle.api.artifacts.transform.TransformAction;
import org.gradle.api.artifacts.transform.TransformOutputs;
import org.gradle.api.artifacts.transform.TransformParameters;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.CompileClasspath;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.process.ExecOperations;

public abstract class RemappingTransform implements TransformAction<RemappingTransform.Parameters> {

    @InputArtifact
    @PathSensitive(PathSensitivity.NONE)
    public abstract Provider<FileSystemLocation> getInputArtifact();

    @CompileClasspath
    @InputArtifactDependencies
    public abstract FileCollection getDependencies();

    @Inject
    protected abstract ExecOperations getExecOperations();

    @Inject
    public RemappingTransform() {}

    @Override
    public void transform(TransformOutputs outputs) {
        var inputFile = getInputArtifact().get().getAsFile();
        // The file may not yet exist if IntelliJ requests it during indexing.
        if (!inputFile.exists()) return;

        var mappedFile = outputs.file(inputFile.getName());
        try {
            getParameters().getRemapOperation()
                    .execute(
                            getExecOperations(),
                            inputFile,
                            mappedFile,
                            getDependencies().plus(getParameters().getMinecraftDependencies()));
            // Strip bundled org.spongepowered.asm.* from non-provider jars. A bundler like VoxelMap ships a FULL old
            // Mixin (incl. tweaker, so content detection can't tell it from a real provider); only the user-declared
            // provider (mixinProvider DSL) is exempt. Without stripping, the old @Inject (no `order` member) shadows
            // MixinBooter 10.7's on the compile classpath.
            if (mappedFile.isFile() && FileUtils.containsSpongePowered(mappedFile)
                    && !getParameters().getMixinProviders().getFiles().contains(inputFile)) {
                FileUtils.stripSpongePowered(mappedFile);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public interface Parameters extends TransformParameters {
        @Nested
        RemapOperation getRemapOperation();

        @InputFiles
        @PathSensitive(PathSensitivity.NONE)
        ConfigurableFileCollection getMinecraftDependencies();

        /** Jars that ARE the declared Mixin provider (via the {@code mixinProvider} DSL) — exempt from sponge-strip. */
        @InputFiles
        @PathSensitive(PathSensitivity.NONE)
        ConfigurableFileCollection getMixinProviders();
    }
}

