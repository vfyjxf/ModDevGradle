package net.neoforged.moddevgradle.mcpforge.internal;

import java.util.Map;
import javax.inject.Inject;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.jvm.tasks.Jar;

public abstract class MixinExtension {
    private final Project project;
    private final Provider<RegularFile> officialToSrg;
    private final ConfigurableFileCollection extraMappingFiles;

    @Inject
    public MixinExtension(Project project,
            Provider<RegularFile> officialToSrg,
            ConfigurableFileCollection extraMappingFiles) {
        this.project = project;
        this.officialToSrg = officialToSrg;
        this.extraMappingFiles = extraMappingFiles;
    }

    public abstract Property<String> getDefaultObfuscationEnv();
    public abstract Property<Boolean> getQuiet();
    public abstract Property<Boolean> getShowMessageTypes();
    public abstract Property<Boolean> getDisableTargetValidator();
    public abstract Property<Boolean> getDisableTargetExport();
    public abstract Property<Boolean> getDisableOverwriteChecker();
    public abstract Property<String> getOverwriteErrorLevel();
    public abstract ConfigurableFileCollection getExtraMappings();
    public abstract MapProperty<String, String> getTokens();
    public abstract MapProperty<String, String> getMessages();
    public abstract ListProperty<String> getConfigs();

    public abstract MapProperty<String, String> getDebugProperties();
    public abstract MapProperty<String, String> getEnvProperties();
    public abstract MapProperty<String, String> getChecksProperties();
    public abstract Property<String> getHotSwap();
    public abstract Property<String> getDumpTargetOnFailure();
    public abstract Property<String> getIgnoreConstraints();
    public abstract Property<String> getInitialiserInjectionMode();

    public void config(String name) { getConfigs().add(name); }
    public void extraMapping(Object file) { getExtraMappings().from(file); }
    public void token(String name) { getTokens().put(name, "true"); }
    public void token(String name, String value) { getTokens().put(name, value); }
    public void tokens(Map<String, String> map) { getTokens().putAll(map); }

    public void quiet() { getQuiet().set(true); }
    public void showMessageTypes() { getShowMessageTypes().set(true); }
    public void disableTargetValidator() { getDisableTargetValidator().set(true); }
    public void disableTargetExport() { getDisableTargetExport().set(true); }
    public void disableOverwriteChecker() { getDisableOverwriteChecker().set(true); }
    public void overwriteErrorLevel(String level) { getOverwriteErrorLevel().set(level); }
    public void messages(Map<String, String> map) { getMessages().putAll(map); }

    public Provider<RegularFile> add(String refmap) {
        return add(getMainSourceSet(), refmap);
    }

    public Provider<RegularFile> add(String sourceSetName, String refmap) {
        return add(project.getExtensions().getByType(org.gradle.api.plugins.JavaPluginExtension.class)
                .getSourceSets().getByName(sourceSetName), refmap);
    }

    public Provider<RegularFile> add(SourceSet sourceSet, String refmap) {
        var mappingFile = project.getLayout().getBuildDirectory().dir("mixin")
                .map(d -> d.file(refmap + ".mappings.tsrg"));
        var refMapFile = project.getLayout().getBuildDirectory().dir("mixin")
                .map(d -> d.file(refmap));

        project.getTasks().named(sourceSet.getCompileJavaTaskName(), JavaCompile.class).configure(compile -> {
            var compilerArgs = project.getObjects().newInstance(MixinCompilerArgs.class);
            compilerArgs.getRefmap().set(refMapFile);
            compilerArgs.getOutMappings().set(mappingFile);
            compilerArgs.getInMappings().set(officialToSrg);
            compilerArgs.getExtraMappings().from(getExtraMappings());
            compilerArgs.getDefaultObfuscationEnv().set(getDefaultObfuscationEnv());
            compilerArgs.getQuiet().set(getQuiet());
            compilerArgs.getShowMessageTypes().set(getShowMessageTypes());
            compilerArgs.getDisableTargetValidator().set(getDisableTargetValidator());
            compilerArgs.getDisableTargetExport().set(getDisableTargetExport());
            compilerArgs.getDisableOverwriteChecker().set(getDisableOverwriteChecker());
            compilerArgs.getOverwriteErrorLevel().set(getOverwriteErrorLevel());
            compilerArgs.getTokens().set(getTokens());
            compilerArgs.getMessages().set(getMessages());
            compile.getOptions().getCompilerArgumentProviders().add(compilerArgs);
        });

        extraMappingFiles.from(mappingFile);

        project.getTasks().withType(Jar.class)
                .matching(jar -> jar.getName().equals(sourceSet.getJarTaskName()))
                .configureEach(jar -> jar.from(refMapFile));

        autoAddAnnotationProcessorDeps(sourceSet);

        return refMapFile;
    }

    private SourceSet getMainSourceSet() {
        return project.getExtensions().getByType(org.gradle.api.plugins.JavaPluginExtension.class)
                .getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
    }

    private void autoAddAnnotationProcessorDeps(SourceSet sourceSet) {
        var apConfig = project.getConfigurations().named(sourceSet.getAnnotationProcessorConfigurationName());
        var depFactory = project.getDependencyFactory();
        apConfig.configure(c -> {
            c.getDependencies().add(depFactory.create("org.ow2.asm:asm-debug-all:5.2"));
            c.getDependencies().add(depFactory.create("com.google.guava:guava:32.1.2-jre"));
            c.getDependencies().add(depFactory.create("com.google.code.gson:gson:2.8.9"));
        });
    }
}
