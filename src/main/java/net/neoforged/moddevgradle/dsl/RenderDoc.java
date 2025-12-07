package net.neoforged.moddevgradle.dsl;

import org.gradle.api.Project;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.jetbrains.annotations.ApiStatus;

import javax.inject.Inject;
import java.io.File;

@ApiStatus.NonExtendable
public abstract class RenderDoc {

    @Inject
    public RenderDoc(Project project) {

        getRenderNurse().convention(
            project.getProviders()
                   .gradleProperty("neoForge.renderdoc.renderNurse")
                   .orElse("net.neoforged:render-nurse:0.0.12")
        );
        getRenderDocVersion().convention(
            project.getProviders()
                   .gradleProperty("neoForge.renderdoc.version")
                   .orElse("1.17")
        );
        getRenderDocPath().convention(
            project.getProviders().gradleProperty("neoForge.renderdoc.path").flatMap(
                p -> project.getLayout().dir(project.provider(() -> new File(p)))
            ).orElse(project.getLayout().getBuildDirectory().dir("renderdoc"))
        );
    }

    /**
     * @return The artifact coordinate for RenderNurse.
     */
    @Input
    @Optional
    public abstract Property<String> getRenderNurse();

    /**
     * @return The artifact version for RenderDoc.
     */
    @Input
    @Optional
    public abstract Property<String> getRenderDocVersion();

    /**
     * @return The path to where RenderDoc is installed, or will be installed if the directory is empty or none-existent
     */
    @Internal
    @Optional
    public abstract DirectoryProperty getRenderDocPath();
}
