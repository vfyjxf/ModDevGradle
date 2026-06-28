package net.neoforged.moddevgradle.internal;

import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ArchiveOperations;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.TaskAction;

public abstract class ExtractNatives extends DefaultTask {
    private final ArchiveOperations archiveOperations;
    private final FileSystemOperations fileSystemOperations;

    @Classpath
    @InputFiles
    @SkipWhenEmpty
    public abstract ConfigurableFileCollection getNativeLibraries();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @Input
    public abstract Property<Boolean> getEnabledForRun();

    @Inject
    public ExtractNatives(ArchiveOperations archiveOperations, FileSystemOperations fileSystemOperations) {
        this.archiveOperations = archiveOperations;
        this.fileSystemOperations = fileSystemOperations;
        getEnabledForRun().convention(false);
    }

    @TaskAction
    public void extract() {
        if (!getEnabledForRun().get()) {
            return;
        }

        fileSystemOperations.delete(spec -> spec.delete(getOutputDirectory()));
        fileSystemOperations.copy(spec -> {
            spec.into(getOutputDirectory());
            spec.setDuplicatesStrategy(DuplicatesStrategy.EXCLUDE);
            for (var nativeLibrary : getNativeLibraries()) {
                if (isLwjgl2Arm64MacosNativePatch(nativeLibrary.getName())) {
                    spec.from(archiveOperations.zipTree(nativeLibrary), copy -> copy.exclude("liblwjgl.dylib"));
                } else {
                    spec.from(archiveOperations.zipTree(nativeLibrary));
                }
            }
        });
        fileSystemOperations.copy(spec -> {
            spec.into(getOutputDirectory());
            for (var nativeLibrary : getNativeLibraries()) {
                if (isLwjgl2Arm64MacosNativePatch(nativeLibrary.getName())) {
                    spec.from(archiveOperations.zipTree(nativeLibrary), copy -> {
                        copy.include("liblwjgl.dylib", "openal.dylib");
                        copy.rename("liblwjgl\\.dylib", "liblwjgl.jnilib");
                    });
                }
            }
        });
    }

    private static boolean isLwjgl2Arm64MacosNativePatch(String fileName) {
        return fileName.equals("lwjgl-platform-2.9.4-nightly-20150209-mmachina.2-natives-osx.jar");
    }
}
