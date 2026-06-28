package net.neoforged.moddevgradle.mcpforge.internal;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.process.CommandLineArgumentProvider;

abstract class MixinCompilerArgs implements CommandLineArgumentProvider {
    @Inject
    public MixinCompilerArgs() {}

    @OutputFile
    protected abstract RegularFileProperty getOutMappings();

    @OutputFile
    protected abstract RegularFileProperty getRefmap();

    @InputFile
    @PathSensitive(PathSensitivity.NAME_ONLY)
    protected abstract RegularFileProperty getInMappings();

    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    protected abstract ConfigurableFileCollection getExtraMappings();

    @Input @Optional
    protected abstract Property<String> getDefaultObfuscationEnv();

    @Input @Optional
    protected abstract Property<Boolean> getQuiet();

    @Input @Optional
    protected abstract Property<Boolean> getShowMessageTypes();

    @Input @Optional
    protected abstract Property<Boolean> getDisableTargetValidator();

    @Input @Optional
    protected abstract Property<Boolean> getDisableTargetExport();

    @Input @Optional
    protected abstract Property<Boolean> getDisableOverwriteChecker();

    @Input @Optional
    protected abstract Property<String> getOverwriteErrorLevel();

    @Input @Optional
    protected abstract MapProperty<String, String> getTokens();

    @Input @Optional
    protected abstract MapProperty<String, String> getMessages();

    @Override
    public Iterable<String> asArguments() {
        var args = new ArrayList<String>();
        args.add("-AreobfSrgFile=" + getInMappings().get().getAsFile().getAbsolutePath());
        args.add("-AoutRefMapFile=" + getRefmap().get().getAsFile().getAbsolutePath());
        args.add("-AdefaultObfuscationEnv=" + getDefaultObfuscationEnv().getOrElse("searge"));

        addFlag(args, getQuiet(), "-Aquiet=true");
        addFlag(args, getShowMessageTypes(), "-AshowMessageTypes=true");
        addFlag(args, getDisableTargetValidator(), "-AdisableTargetValidator=true");
        addFlag(args, getDisableTargetExport(), "-AdisableTargetExport=true");
        addFlag(args, getDisableOverwriteChecker(), "-AdisableOverwriteChecker=true");

        if (getOverwriteErrorLevel().isPresent()) {
            args.add("-AoverwriteErrorLevel=" + getOverwriteErrorLevel().get());
        }

        if (!getExtraMappings().getFiles().isEmpty()) {
            var sb = new StringBuilder();
            for (var file : getExtraMappings().getFiles()) {
                if (sb.length() > 0) sb.append(",");
                sb.append(file.getAbsolutePath());
            }
            args.add("-AreobfTsrgFiles=" + sb);
        }

        if (getTokens().isPresent() && !getTokens().get().isEmpty()) {
            var sb = new StringBuilder();
            for (var entry : getTokens().get().entrySet()) {
                if (sb.length() > 0) sb.append(";");
                sb.append(entry.getKey()).append("=").append(entry.getValue());
            }
            args.add("-Atokens=" + sb);
        }

        if (getMessages().isPresent()) {
            for (var entry : getMessages().get().entrySet()) {
                if (entry.getKey().matches("^[A-Z]+[A-Z_]+$") && entry.getValue().matches("^(note|warning|error|disabled)$")) {
                    args.add("-AMSG_" + entry.getKey() + "=" + entry.getValue());
                }
            }
        }

        return args;
    }

    private static void addFlag(List<String> args, Property<Boolean> flag, String arg) {
        if (flag.isPresent() && flag.get()) {
            args.add(arg);
        }
    }
}
