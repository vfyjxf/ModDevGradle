package net.neoforged.moddevgradle.internal;

import net.neoforged.moddevgradle.internal.utils.FileDownloadingUtils;
import net.neoforged.moddevgradle.internal.utils.OperatingSystem;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ArchiveOperations;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.internal.impldep.org.apache.commons.io.FileUtils;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.zip.ZipFile;

abstract class DownloadRenderDocTask extends DefaultTask {


    public DownloadRenderDocTask() {
        getIsOffline().set(getProject().getGradle().getStartParameter().isOffline());
        getRenderDocVersion().convention("1.33"); // Current default.
        getRenderDocOutputDirectory().convention(getProject().getLayout().getBuildDirectory().dir("renderdoc/download"));
        getRenderDocInstallationDirectory().convention(getProject().getLayout().getBuildDirectory().dir("renderdoc/installation"));
        getRenderDocLibraryFile().fileProvider(
            getRenderDocInstallationDirectory().map(dir -> getOSSpecificRenderDocLibraryFile(dir.getAsFile()))
        );

        getOutputs().upToDateWhen(task -> false);
    }

    @TaskAction
    public void doDownload() throws IOException {
        final File outputRoot = getRenderDocInstallationDirectory().get().getAsFile();
        if (outputRoot.exists() && outputRoot.isDirectory()) {
            final File renderDocLibraryFile = getOSSpecificRenderDocLibraryFile(outputRoot);
            if (renderDocLibraryFile.exists() && renderDocLibraryFile.isFile()) {
                setDidWork(false);
                return;
            }
        }

        final String url = getOSSpecificRenderDocUrl();

        final File output = getRenderDocInstallationDirectory().get().getAsFile();
        if (output.exists()) {
            if (output.isFile()) {
                output.delete();
                output.mkdirs();
            } else {
                FileUtils.cleanDirectory(output);
            }
        } else {
            output.mkdirs();
        }

        final FileDownloadingUtils.DownloadInfo downloadInfo = new FileDownloadingUtils.DownloadInfo(url, null, null, null, null);
        final File compressedDownloadTarget = new File(getRenderDocOutputDirectory().get().getAsFile(), getOSSpecificFileName());
        FileDownloadingUtils.downloadTo(getIsOffline().getOrElse(false), downloadInfo, compressedDownloadTarget);

        extractOSSpecific(compressedDownloadTarget);
    }

    @Input
    @Optional
    public abstract Property<Boolean> getIsOffline();

    @Input
    public abstract Property<String> getRenderDocVersion();

    @OutputDirectory
    public abstract DirectoryProperty getRenderDocOutputDirectory();

    @Internal
    public abstract DirectoryProperty getRenderDocInstallationDirectory();

    @Internal
    public abstract RegularFileProperty getRenderDocLibraryFile();


    private File getOSSpecificRenderDocLibraryFile(final File root) {
        if (OperatingSystem.current() == OperatingSystem.WINDOWS) {
            return new File(root, "RenderDoc_%s_64/renderdoc.dll".formatted(getRenderDocVersion().get()));
        }

        if (OperatingSystem.current() == OperatingSystem.LINUX) {
            return new File(root, "renderdoc_%s/lib/librenderdoc.so".formatted(getRenderDocVersion().get()));
        }

        throw new IllegalStateException("Unsupported OS: " + OperatingSystem.current().name());
    }

    private File getOSSpecificRenderDocExecutableFile(final File root) {
        if (OperatingSystem.current() == OperatingSystem.WINDOWS) {
            throw new IllegalStateException("Not implemented yet");
        }

        if (OperatingSystem.current() == OperatingSystem.LINUX) {
            return new File(root, "renderdoc_%s/bin/qrenderdoc".formatted(getRenderDocVersion().get()));
        }

        throw new IllegalStateException("Unsupported OS: " + OperatingSystem.current().name());
    }

    private String getOSSpecificRenderDocUrl() {
        if (OperatingSystem.current() == OperatingSystem.WINDOWS) {
            return "https://renderdoc.org/stable/1.33/RenderDoc_%s_64.zip".formatted(getRenderDocVersion().get());
        }

        if (OperatingSystem.current() == OperatingSystem.LINUX) {
            return "https://renderdoc.org/stable/1.33/renderdoc_%s.tar.gz".formatted(getRenderDocVersion().get());
        }

        throw new IllegalStateException("Unsupported OS: " + OperatingSystem.current().name());
    }

    private String getOSSpecificFileName() {
        if (OperatingSystem.current() == OperatingSystem.WINDOWS) {
            return "renderdoc.zip";
        }

        if (OperatingSystem.current() == OperatingSystem.LINUX) {
            return "renderdoc.tar.gz";
        }

        throw new IllegalStateException("Unsupported OS: " + OperatingSystem.current().name());
    }

    private void extractOSSpecific(final File input) {
        if (OperatingSystem.current() == OperatingSystem.WINDOWS) {
            extractWindows(input);
        } else if (OperatingSystem.current() == OperatingSystem.LINUX) {
            extractLinux(input);
        } else {
            throw new IllegalStateException("Unsupported OS: " + OperatingSystem.current().name());
        }
    }

    private void extractWindows(final File input) {
        final File output = getRenderDocInstallationDirectory().get().getAsFile();

        unpack(input, output);
    }

    private void extractLinux(final File input) {
        final File output = getRenderDocInstallationDirectory().get().getAsFile();

        unpack(input, output);

        final File executable = getOSSpecificRenderDocExecutableFile(output);
        executable.setExecutable(true);
    }

    private static void unpack(final File input, final File output) {
        try (final ZipFile zipFile = new ZipFile(input)) {
            zipFile.stream().forEach(entry -> {
                final File file = new File(output, entry.getName());
                if (entry.isDirectory()) {
                    file.mkdirs();
                } else {
                    file.getParentFile().mkdirs();
                    try (final InputStream inputStream = zipFile.getInputStream(entry)) {
                        Files.copy(inputStream, file.toPath());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
