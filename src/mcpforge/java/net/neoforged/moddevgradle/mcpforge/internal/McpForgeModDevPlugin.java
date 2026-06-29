package net.neoforged.moddevgradle.mcpforge.internal;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import javax.inject.Inject;
import net.neoforged.minecraftdependencies.MinecraftDependenciesPlugin;
import net.neoforged.moddevgradle.internal.ArtifactNamingStrategy;
import net.neoforged.moddevgradle.internal.Branding;
import net.neoforged.moddevgradle.internal.DataFileCollections;
import net.neoforged.moddevgradle.internal.McpToolchainHooks;
import net.neoforged.moddevgradle.internal.ModDevArtifactsWorkflow;
import net.neoforged.moddevgradle.internal.ModDevRunWorkflow;
import net.neoforged.moddevgradle.internal.ModdingDependencies;
import net.neoforged.moddevgradle.internal.RunGameTask;
import net.neoforged.moddevgradle.internal.jarjar.JarJarPlugin;
import net.neoforged.moddevgradle.internal.utils.ExtensionUtils;
import net.neoforged.moddevgradle.internal.utils.StringUtils;
import net.neoforged.moddevgradle.internal.utils.VersionCapabilitiesInternal;
import net.neoforged.moddevgradle.legacyforge.tasks.PopulateForgeGradleMcpCache;
import net.neoforged.moddevgradle.mcpforge.dsl.McpForgeModdingSettings;
import net.neoforged.moddevgradle.legacyforge.dsl.ObfuscationExtension;
import net.neoforged.moddevgradle.legacyforge.internal.LegacyForgeLibraryMetadataRule;
import net.neoforged.moddevgradle.legacyforge.internal.LegacyRepositoriesPlugin;
import net.neoforged.moddevgradle.legacyforge.internal.MinecraftMappings;
import net.neoforged.moddevgradle.legacyforge.internal.NonStrictDependencyTransform;
import net.neoforged.moddevgradle.mcpforge.dsl.McpForgeExtension;
import net.neoforged.nfrtgradle.CreateMinecraftArtifacts;
import net.neoforged.nfrtgradle.NeoFormRuntimeExtension;
import net.neoforged.nfrtgradle.NeoFormRuntimePlugin;
import org.gradle.api.InvalidUserCodeException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.dsl.DependencyFactory;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.JavaLibraryPlugin;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.jvm.tasks.Jar;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The MCP-based Forge toolchain for 1.12.2 and other legacy versions (plugin id {@code net.neoforged.moddev.mcpforge}).
 *
 * <p>This plugin owns its pipeline independently (it does NOT apply the {@code legacyforge} plugin), reusing legacy's
 * public classes and a set of MCP-specific SPI hooks (LWJGL2 Apple Silicon natives, Forge deobf-data remapping,
 * coremod discovery, launchwrapper Java 8 runtime, pre-1.13 resource merge, SRG refmap).
 */
public class McpForgeModDevPlugin implements Plugin<Project> {
    private static final Logger LOG = LoggerFactory.getLogger(McpForgeModDevPlugin.class);

    public static final String MIXIN_EXTENSION = "mixin";
    public static final String OBFUSCATION_EXTENSION = "obfuscation";
    public static final String MCPFORGE_EXTENSION = "mcpForge";

    public static final String CONFIGURATION_TOOL_ART = "autoRenamingToolRuntime";
    public static final String CONFIGURATION_TOOL_INSTALLERTOOLS = "installerToolsRuntime";
    private static final String LEGACY_MCP_NFRT_EXAMPLE = "2.0.19-legacy";

    private final MinecraftMappings namedMappings;
    private final MinecraftMappings srgMappings;

    @Inject
    public McpForgeModDevPlugin(ObjectFactory objectFactory) {
        namedMappings = objectFactory.named(MinecraftMappings.class, MinecraftMappings.NAMED);
        srgMappings = objectFactory.named(MinecraftMappings.class, MinecraftMappings.SRG);
    }

    @Override
    public void apply(Project project) {
        // Base plugins
        project.getPlugins().apply(JavaLibraryPlugin.class);
        project.getPlugins().apply(NeoFormRuntimePlugin.class);
        project.getPlugins().apply(MinecraftDependenciesPlugin.class);
        project.getPlugins().apply(JarJarPlugin.class);

        // Skip applying repositories at the project level if they were already applied at settings level.
        if (!project.getGradle().getPlugins().hasPlugin(LegacyRepositoriesPlugin.class)) {
            project.getPlugins().apply(LegacyRepositoriesPlugin.class);
        } else {
            LOG.info("Not enabling legacy repositories since they were applied at the settings level");
        }

        // Apple Silicon LWJGL2 native repos (GitHub releases by MinecraftMachina). Needed for 1.12.2 on ARM64 macOS.
        var repos = project.getRepositories();
        repos.ivy(repo -> {
            repo.setName("MinecraftMachina Apple Silicon LWJGL2");
            repo.setUrl(URI.create("https://github.com/MinecraftMachina/lwjgl/releases/download/2.9.4-20150209-mmachina.2/"));
            repo.patternLayout(layout -> layout.artifact("[module]-2.9.4-nightly-20150209-[classifier].[ext]"));
            repo.metadataSources(sources -> sources.artifact());
            repo.content(content -> {
                content.includeModule("org.lwjgl.lwjgl", "lwjgl");
                content.includeModule("org.lwjgl.lwjgl", "lwjgl-platform");
                content.includeModule("org.lwjgl.lwjgl", "lwjgl_util");
            });
        });
        repos.ivy(repo -> {
            repo.setName("MinecraftMachina Java Objective-C Bridge");
            repo.setUrl(URI.create("https://github.com/MinecraftMachina/Java-Objective-C-Bridge/releases/download/1.1.0-mmachina.1/"));
            repo.patternLayout(layout -> layout.artifact("[module]-1.1.[ext]"));
            repo.metadataSources(sources -> sources.artifact());
            repo.content(content -> content.includeModule("ca.weblite", "java-objc-bridge"));
        });

        // Metadata transforms
        project.getDependencies().getComponents().withModule("net.minecraftforge:forge", LegacyForgeMetadataTransform.class);
        project.getDependencies().getComponents().withModule("net.minecraftforge:forge", LegacyForgeLibraryMetadataRule.class);
        project.getDependencies().getComponents().withModule("de.oceanlabs.mcp:mcp_config", McpMetadataTransform.class);
        // Legacy Forge upgrades some deps (e.g. log4j2); relax the strict version requirements we can't otherwise fix.
        project.getDependencies().getComponents().withModule("net.neoforged:minecraft-dependencies", NonStrictDependencyTransform.class);

        // Tool configurations
        var depFactory = project.getDependencyFactory();
        var autoRenamingToolRuntime = project.getConfigurations().create(CONFIGURATION_TOOL_ART, spec -> {
            spec.setDescription("The AutoRenamingTool CLI tool");
            spec.setCanBeConsumed(false);
            spec.setCanBeResolved(true);
            spec.setTransitive(false);
            spec.getDependencies().add(depFactory.create("net.neoforged:AutoRenamingTool:2.0.4:all"));
        });
        var installerToolsRuntime = project.getConfigurations().create(CONFIGURATION_TOOL_INSTALLERTOOLS, spec -> {
            spec.setDescription("The InstallerTools CLI tool");
            spec.setCanBeConsumed(false);
            spec.setCanBeResolved(true);
            spec.setTransitive(false);
            spec.getDependencies().add(depFactory.create("net.neoforged.installertools:installertools:3.0.4:fatjar"));
        });

        // Extensions. extraMixinMappings shares mixin-generated mappings with the obfuscation extension.
        var extraMixinMappings = project.files();
        var obf = project.getExtensions().create(OBFUSCATION_EXTENSION, ObfuscationExtension.class, project, autoRenamingToolRuntime, installerToolsRuntime, extraMixinMappings);
        var mixin = project.getExtensions().create(MIXIN_EXTENSION, MixinExtension.class, project, obf.getNamedToSrgMappings(), extraMixinMappings);
        // Defaults matching MixinGradle.
        mixin.getDefaultObfuscationEnv().convention("searge");
        mixin.getQuiet().convention(false);
        mixin.getShowMessageTypes().convention(false);
        mixin.getDisableTargetValidator().convention(false);
        mixin.getDisableTargetExport().convention(false);
        mixin.getDisableOverwriteChecker().convention(false);

        configureDependencyRemapping(project, obf);

        var dataFileCollections = DataFileCollections.create(project);
        project.getExtensions().create(
                MCPFORGE_EXTENSION,
                McpForgeExtension.class,
                project,
                dataFileCollections.accessTransformers().extension(),
                dataFileCollections.interfaceInjectionData().extension());


        // Register MCP hooks so the main workflow picks up LWJGL2 natives.
        project.getExtensions().add(McpToolchainHooks.EXTENSION_NAME, new McpHooks());

        // Collect Access Transformers declared by dependency jars via their FMLAT manifest (FG2/RFG parity).
        configureDependencyAccessTransformers(project);

        // Configure RunGameTask tasks (lazy — matches whenever they are created).
        configureRunTasks(project);
    }


    public void enable(Project project, McpForgeModdingSettings settings, McpForgeExtension extension) {
        var depFactory = project.getDependencyFactory();

        var forgeVersion = settings.getForgeVersion();
        var neoForgeVersion = settings.getNeoForgeVersion();
        var mcpVersion = settings.getMcpVersion();

        ModdingDependencies dependencies;
        ArtifactNamingStrategy artifactNamingStrategy;
        VersionCapabilitiesInternal versionCapabilities;
        if (forgeVersion != null || neoForgeVersion != null) {
            // All settings are mutually exclusive
            if (forgeVersion != null && neoForgeVersion != null || mcpVersion != null) {
                throw new InvalidUserCodeException("Specifying a Forge version is mutually exclusive with NeoForge or MCP");
            }

            var version = forgeVersion != null ? forgeVersion : neoForgeVersion;
            versionCapabilities = VersionCapabilitiesInternal.ofForgeVersion(version);
            validateNeoFormRuntimeSupport(project, versionCapabilities);
            artifactNamingStrategy = ArtifactNamingStrategy.createNeoForge(versionCapabilities, "forge", version);

            String groupId = forgeVersion != null ? "net.minecraftforge" : "net.neoforged";
            var neoForge = depFactory.create(groupId + ":forge:" + version);
            var neoForgeNotation = LegacyForgeArtifacts.userdevNotation(groupId, version, versionCapabilities);
            dependencies = ModdingDependencies.create(neoForge, neoForgeNotation, null, null, versionCapabilities);
        } else if (mcpVersion != null) {
            versionCapabilities = VersionCapabilitiesInternal.ofMinecraftVersion(mcpVersion);
            artifactNamingStrategy = ArtifactNamingStrategy.createVanilla(mcpVersion);

            var neoForm = depFactory.create("de.oceanlabs.mcp:mcp_config:" + mcpVersion);
            var neoFormNotation = "de.oceanlabs.mcp:mcp_config:" + mcpVersion + "@zip";
            dependencies = ModdingDependencies.createVanillaOnly(neoForm, neoFormNotation);
        } else {
            throw new InvalidUserCodeException("You must specify a Forge, NeoForge or MCP version");
        }

        var configurations = project.getConfigurations();

        var artifacts = ModDevArtifactsWorkflow.create(
                project,
                settings.getEnabledSourceSets(),
                Branding.MDG,
                extension,
                dependencies,
                artifactNamingStrategy,
                configurations.getByName(DataFileCollections.CONFIGURATION_ACCESS_TRANSFORMERS),
                configurations.getByName(DataFileCollections.CONFIGURATION_INTERFACE_INJECTION_DATA),
                versionCapabilities,
                false, // disableRecompilation is not supported by mcpforge (no-recompile/binary-patch path is not viable for 1.12.2/MCP)
                settings.getMcpMappings());

        // Configure the mixin and obfuscation extensions.
        var mixin = ExtensionUtils.getExtension(project, MIXIN_EXTENSION, MixinExtension.class);
        var obf = ExtensionUtils.getExtension(project, OBFUSCATION_EXTENSION, ObfuscationExtension.class);

        var namedToIntermediate = artifacts.requestAdditionalMinecraftArtifact("namedToIntermediaryMapping", "namedToIntermediate.tsrg");
        obf.getNamedToSrgMappings().set(namedToIntermediate);
        var intermediateToNamed = artifacts.requestAdditionalMinecraftArtifact("intermediaryToNamedMapping", "intermediateToNamed.srg");
        var mappingsCsv = artifacts.requestAdditionalMinecraftArtifact("csvMapping", "intermediateToNamed.zip");
        obf.getSrgToNamedMappings().set(mappingsCsv);
        var notchToIntermediate = artifacts.requestAdditionalMinecraftArtifact("notchToIntermediaryMapping", "notchToIntermediate.srg");

        // ForgeGradle-2 compatibility: mirror the MCP data into the FG-2 cache layout
        // (~/.gradle/caches/minecraft/de/oceanlabs/mcp/<name>/<version>/) so legacy tooling/scripts that hardcode the
        // FG-2 path keep working. Only done for legacy MCP versions (e.g. 1.12.2).
        if (settings.getMcpMappings() != null) {
            // Resolve the FG-2 cache base dir at configuration time so the task stays config-cache compatible.
            var mcpCoordinate = settings.getMcpMappings();
            var gradleUserHome = project.getGradle().getGradleUserHomeDir().toPath().toAbsolutePath().toString();
            var fg2CacheBase = project.getProviders().provider(() -> {
                var withoutExt = mcpCoordinate.indexOf('@') >= 0 ? mcpCoordinate.substring(0, mcpCoordinate.indexOf('@')) : mcpCoordinate;
                var parts = withoutExt.split(":");
                var name = parts.length > 1 ? parts[1] : "mcp";
                var rawVersion = parts.length > 2 ? parts[2] : "unknown";
                var version = rawVersion.indexOf('-') >= 0 ? rawVersion.substring(0, rawVersion.indexOf('-')) : rawVersion;
                return Path.of(gradleUserHome, "caches", "minecraft", "de", "oceanlabs", "mcp", name, version).toString();
            });
            project.getTasks().register("populateForgeGradleMcpCache", PopulateForgeGradleMcpCache.class, task -> {
                task.setGroup(Branding.MDG.internalTaskGroup());
                task.setDescription("Populates the ForgeGradle-2 MCP cache directory with ModDevGradle's MCP data for legacy-tool compatibility.");
                task.getMcpMappings().set(mcpCoordinate);
                task.getMinecraftVersion().set(versionCapabilities.minecraftVersion());
                task.getCacheBaseDirectory().set(fg2CacheBase);
                task.getCsvMappings().set(mappingsCsv);
                task.getSrgToMcpMappings().set(intermediateToNamed);
                task.getMcpToSrgMappings().set(namedToIntermediate);
                task.getNotchToSrgMappings().set(notchToIntermediate);
            });
            project.getTasks().named("createMinecraftArtifacts", task -> task.finalizedBy("populateForgeGradleMcpCache"));
        }

        var runs = ModDevRunWorkflow.create(
                project,
                Branding.MDG,
                artifacts,
                extension.getRuns(),
                Map.of(
                        "mcp_to_srg", intermediateToNamed.map(file -> file.getAsFile().getAbsolutePath()),
                        "mcp_mappings", mappingsCsv.map(file -> file.getAsFile().getAbsolutePath())));

        extension.getRuns().configureEach(run -> {
            // Old BSL versions before 2022 did not export any packages, blocking DevLaunch from the main method.
            if (versionCapabilities.javaVersion() > 8) {
                run.getJvmArguments().addAll("--add-exports", "cpw.mods.bootstraplauncher/cpw.mods.bootstraplauncher=ALL-UNNAMED");
            }
            if ("1.12.2".equals(versionCapabilities.minecraftVersion())) {
                run.getSystemProperties().put("fml.ignorePatchDiscrepancies", "true");
                run.getSystemProperties().put("fml.ignoreInvalidMinecraftCertificates", "true");
            }

            if (!versionCapabilities.modLocatorRework()) {
                // Pre-1.13 FML only loads a mod's resources from the @Mod class' source directory, so Gradle's split
                // output (classes vs resources) leaves assets/mcmod.info/lang missing at runtime. Colocate them.
                var modSourceSet = run.getSourceSet().get();
                modSourceSet.getOutput().setResourcesDir(modSourceSet.getJava().getDestinationDirectory().get().getAsFile());
            }

            // Mixin needs the SRG->named mapping in SRG (not TSRG) format to ignore dependency refmaps.
            run.getSystemProperties().put("mixin.env.remapRefMap", "true");
            run.getSystemProperties().put("mixin.env.refMapRemappingFile", intermediateToNamed.map(f -> f.getAsFile().getAbsolutePath()));

            run.getProgramArguments().addAll(mixin.getConfigs().map(cfgs -> cfgs.stream().flatMap(config -> Stream.of("--mixin.config", config)).toList()));
        });

        if (settings.isObfuscateJar()) {
            var reobfJar = obf.reobfuscate(
                    project.getTasks().named(JavaPlugin.JAR_TASK_NAME, Jar.class),
                    project.getExtensions().getByType(SourceSetContainer.class).getByName(SourceSet.MAIN_SOURCE_SET_NAME));

            project.getTasks().named("assemble", assemble -> assemble.dependsOn(reobfJar));
        }

        // Forge expects the mapping csv files on the root classpath.
        artifacts.runtimeDependencies()
                .getDependencies().add(project.getDependencyFactory().create(project.files(mappingsCsv)));

        var remapDeps = project.getConfigurations().create("remappingDependencies", spec -> {
            spec.setDescription("An internal configuration that contains the Minecraft dependencies, used for remapping mods");
            spec.setCanBeConsumed(false);
            spec.setCanBeDeclared(false);
            spec.setCanBeResolved(true);
            spec.extendsFrom(artifacts.runtimeDependencies());
        });

        // Resolve the declared Mixin provider coordinates (mixinProvider DSL) to their raw SRG jars so the
        // RemappingTransform can exempt them from the bundled-spongepowered strip. Resolved lazily.
        var mixinProviderConfig = project.getConfigurations().create("mcpMixinProviders", spec -> {
            spec.setDescription("Mixin provider jars — exempt from bundled-spongepowered stripping");
            spec.setCanBeConsumed(false);
            spec.setCanBeResolved(true);
            spec.setTransitive(false);
            spec.getAttributes().attribute(MinecraftMappings.ATTRIBUTE, srgMappings);
        });
        // Defer reading the mixinProvider list to resolution time so the build script can call mixinProvider() before
        // OR after enable().
        mixinProviderConfig.withDependencies(deps ->
                extension.getMixinProviders().get().forEach(notation ->
                        deps.add(project.getDependencyFactory().create(notation))));

        // The RemappingTransform strips bundled org.spongepowered.asm.* from non-provider jars (post-remap) so an old
        // bundled Mixin can't shadow the declared provider.
        project.getDependencies().registerTransform(RemappingTransform.class, params -> {
            params.parameters(parameters -> {
                obf.configureSrgToNamedOperation(parameters.getRemapOperation());
                parameters.getMinecraftDependencies().from(remapDeps);
                parameters.getMixinProviders().from(mixinProviderConfig);
            });
            params.getFrom()
                    .attribute(MinecraftMappings.ATTRIBUTE, srgMappings)
                    .attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE);
            params.getTo()
                    .attribute(MinecraftMappings.ATTRIBUTE, namedMappings)
                    .attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE);
        });
    }

    private static void validateNeoFormRuntimeSupport(Project project, VersionCapabilitiesInternal versionCapabilities) {
        if (!"1.12.2".equals(versionCapabilities.minecraftVersion())) {
            return;
        }

        var neoFormRuntime = ExtensionUtils.getExtension(project, NeoFormRuntimeExtension.NAME, NeoFormRuntimeExtension.class);
        var nfrtVersion = neoFormRuntime.getVersion().get();
        if (!isKnownIncompatibleWithForge1122(nfrtVersion)) {
            return;
        }

        throw new InvalidUserCodeException("""
                Forge 1.12.2 requires NeoFormRuntime with legacy MCP support. The selected NeoFormRuntime version '%s' does not provide the --mcp-mappings CLI option used for Forge userdev3.
                Set the Gradle property 'neoForge.neoFormRuntime.version' or the neoFormRuntime.version extension property to a compatible NFRT build, such as '%s', until that support is available in the default NFRT release.""".formatted(nfrtVersion, LEGACY_MCP_NFRT_EXAMPLE));
    }

    private static boolean isKnownIncompatibleWithForge1122(String nfrtVersion) {
        return "2.0.18".equals(nfrtVersion) || "2.0.19".equals(nfrtVersion);
    }

    private void configureDependencyRemapping(Project project, ObfuscationExtension obf) {
        // JarJar cross-project deps must be remapped to SRG without affecting external deps (already in the right
        // namespace). Requesting the srg attribute on cross-project deps excludes the named variant from selection.
        var sourceSets = ExtensionUtils.getSourceSets(project);
        sourceSets.all(sourceSet -> {
            var configurationName = sourceSet.getTaskName(null, "jarJar");
            project.getConfigurations().getByName(configurationName).withDependencies(dependencies -> {
                dependencies.forEach(dep -> {
                    if (dep instanceof ProjectDependency projectDependency) {
                        projectDependency.attributes(a -> {
                            a.attribute(MinecraftMappings.ATTRIBUTE, srgMappings);
                        });
                    }
                });
            });
        });

        project.getDependencies().attributesSchema(schema -> {
            var attr = schema.attribute(MinecraftMappings.ATTRIBUTE);
            // Prefer named variants for cross-project deps where both named and obfuscated variants are available.
            attr.getDisambiguationRules().add(MappingsDisambiguationRule.class, config -> {
                config.params(namedMappings);
            });
        });
        // Give every jar the srg attribute so it can be force-remapped by requesting named.
        project.getDependencies().getArtifactTypes().named(ArtifactTypeDefinition.JAR_TYPE, type -> {
            type.getAttributes().attribute(MinecraftMappings.ATTRIBUTE, srgMappings);
        });

        // Loom-style transitive mod* configurations: a modImplementation dep pulls its own transitives (e.g.
        // CraftTweaker2-Main -> API -> ZenScript). Transitives resolve to the default SRG variant and are remapped to
        // named by the SRG->named transform, triggered because the classpath configurations request named (below).
        createTransitiveRemappingConfiguration(project, project.getConfigurations().getByName(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME));
        createTransitiveRemappingConfiguration(project, project.getConfigurations().getByName(JavaPlugin.RUNTIME_ONLY_CONFIGURATION_NAME));
        createTransitiveRemappingConfiguration(project, project.getConfigurations().getByName(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME));
        createTransitiveRemappingConfiguration(project, project.getConfigurations().getByName(JavaPlugin.API_CONFIGURATION_NAME));
        createTransitiveRemappingConfiguration(project, project.getConfigurations().getByName(JavaPlugin.COMPILE_ONLY_API_CONFIGURATION_NAME));

        // Request the named variant on the classpath configurations so the SRG->named transform fires on every jar
        // in the graph. Non-MC libs (guava, etc.) have no SRG names, so the transform is a no-op/cache-hit for them.
        for (var configName : new String[]{
                JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME,
                JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME,
                "testCompileClasspath",
                "testRuntimeClasspath"}) {
            project.getConfigurations().named(configName, c ->
                    c.getAttributes().attribute(MinecraftMappings.ATTRIBUTE, namedMappings));
        }
    }

    /**
     * Creates a transitive {@code mod<Parent>} configuration (e.g. {@code modImplementation}) that the parent extends
     * from. Remapping is driven by the classpath configurations requesting the named attribute.
     */
    private static void createTransitiveRemappingConfiguration(Project project, Configuration parent) {
        var modConfig = project.getConfigurations().create(
                "mod" + StringUtils.capitalize(parent.getName()), spec -> {
                    spec.setDescription("Mod dependencies of " + parent.getName() + " (transitive, remapped SRG->named)");
                    spec.setCanBeConsumed(false);
                    spec.setCanBeResolved(false);
                    spec.setTransitive(true);
                });
        parent.extendsFrom(modConfig);
    }

    /**
     * Extracts Access Transformers declared via the {@code FMLAT} manifest attribute in dependency jars and feeds
     * them into the {@code accessTransformers} DataFileCollection so NFRT bakes them into the recompiled MC source.
     */
    private void configureDependencyAccessTransformers(Project project) {
        var atScan = project.getConfigurations().create("mcpAccessTransformerScan", c -> {
            c.setDescription("Mod dependencies scanned for FMLAT access transformers (raw SRG variant, no remapping)");
            c.setCanBeConsumed(false);
            c.setCanBeResolved(true);
            c.getAttributes().attribute(
                    MinecraftMappings.ATTRIBUTE,
                    project.getObjects().named(MinecraftMappings.class, MinecraftMappings.SRG));
        });
        for (var name : new String[]{"modImplementation", "modCompileOnly", "modRuntimeOnly"}) {
            var bucket = project.getConfigurations().findByName(name);
            if (bucket != null) {
                atScan.extendsFrom(bucket);
            }
        }

        var extractDepAts = project.getTasks().register(
                "extractDependencyAccessTransformers", ExtractDependencyAccessTransformers.class, task -> {
                    task.setGroup(Branding.MDG.internalTaskGroup());
                    task.setDescription("Extracts Access Transformers declared via FMLAT manifests in dependency jars (FG2/RFG parity).");
                    task.getOutputDirectory().set(project.getLayout().getBuildDirectory().dir("moddev/dependencyATs"));
                    task.getDependencies().from(atScan);
                });

        var lfExt = project.getExtensions().getByType(McpForgeExtension.class);
        lfExt.getAccessTransformers().from(extractDepAts.map(t -> t.getOutputDirectory().getAsFileTree()));
        project.getTasks().withType(CreateMinecraftArtifacts.class).configureEach(t -> t.dependsOn(extractDepAts));
    }

    private void configureRunTasks(Project project) {
        project.getTasks().withType(RunGameTask.class).configureEach(task -> {
            // launchwrapper requires Java 8; force a Java 8 launcher when the project toolchain is > 8 (e.g. Jabel).
            var javaExt = project.getExtensions().findByType(JavaPluginExtension.class);
            if (javaExt != null && javaExt.getToolchain() != null) {
                var tv = javaExt.getToolchain().getLanguageVersion();
                if (tv.isPresent() && tv.get().asInt() > 8) {
                    var tsObj = project.getExtensions().findByName("javaToolchains");
                    if (tsObj instanceof JavaToolchainService ts) {
                        task.getJavaLauncher().set(ts.launcherFor(spec ->
                                spec.getLanguageVersion().set(JavaLanguageVersion.of(8))));
                    }
                }
            }


            // Coremod discovery + FG2 cache properties: scan classpathProvider at doFirst time.
            task.doFirst(t -> {
                var cp = task.getClasspathProvider().getFiles();

                // The cache task only exists when mcpMappings is configured (legacy MCP builds).
                var cacheTask = (PopulateForgeGradleMcpCache) project.getTasks().findByName("populateForgeGradleMcpCache");
                if (cacheTask != null) {
                    var cacheBase = cacheTask.getCacheBaseDirectory().get();
                    var srgsDir = Path.of(cacheBase, cacheTask.getMinecraftVersion().get(), "srgs");
                    if (Files.isDirectory(srgsDir)) {
                        // Mirror FG-2's GradleStartCommon: expose every SRG map it would have generated plus the
                        // CSV dir, so 1.12.2 mods (e.g. CodeChickenLib) that read these at runtime resolve correctly.
                        task.systemProperty("net.minecraftforge.gradle.GradleStart.srgDir", srgsDir.toString());
                        task.systemProperty("net.minecraftforge.gradle.GradleStart.csvDir", cacheBase);
                        putSrgProperty(task, srgsDir, "notch-srg", "net.minecraftforge.gradle.GradleStart.srg.notch-srg");
                        putSrgProperty(task, srgsDir, "notch-mcp", "net.minecraftforge.gradle.GradleStart.srg.notch-mcp");
                        putSrgProperty(task, srgsDir, "srg-mcp", "net.minecraftforge.gradle.GradleStart.srg.srg-mcp");
                        putSrgProperty(task, srgsDir, "mcp-srg", "net.minecraftforge.gradle.GradleStart.srg.mcp-srg");
                        putSrgProperty(task, srgsDir, "mcp-notch", "net.minecraftforge.gradle.GradleStart.srg.mcp-notch");
                    }
                }

                var coremodClasses = discoverCoremods(project, cp);
                var existing = task.getSystemProperties().get("fml.coreMods.load");
                if (existing instanceof String s && !s.isBlank()) {
                    for (var c : s.split(",")) {
                        if (!c.isBlank()) coremodClasses.add(c.trim());
                    }
                }
                if (!coremodClasses.isEmpty()) {
                    var joined = String.join(",", coremodClasses);
                    project.getLogger().lifecycle("MCP coremod discovery: fml.coreMods.load={}", joined);
                    task.systemProperty("fml.coreMods.load", joined);
                }
            });
        });
    }

    private static void putSrgProperty(RunGameTask task, Path srgsDir, String fileName, String key) {
        var file = srgsDir.resolve(fileName + ".srg");
        if (Files.exists(file)) {
            task.systemProperty(key, file.toString());
        }
    }

    private static Set<String> discoverCoremods(Project project, Set<File> files) {
        var coremodClasses = new LinkedHashSet<String>();
        coremodClasses.addAll(scanManifests(files));
        var fromProperty = resolveCoreModClassProperty(project);
        if (fromProperty != null) {
            coremodClasses.add(fromProperty);
        }
        return coremodClasses;
    }

    private static @Nullable String resolveCoreModClassProperty(Project project) {
        var coreModPluginPath = project.findProperty("coreModPluginPath");
        if (coreModPluginPath != null) {
            var path = coreModPluginPath.toString().trim();
            if (!path.isEmpty()) {
                return path;
            }
        }
        var coreModClass = project.findProperty("coreModClass");
        if (coreModClass == null) {
            return null;
        }
        var value = coreModClass.toString().trim();
        if (value.isEmpty()) {
            return null;
        }
        var modGroup = project.findProperty("modGroup");
        var prefix = modGroup != null && !modGroup.toString().isBlank()
                ? modGroup.toString().trim()
                : project.getGroup().toString();
        if (prefix.isEmpty()) {
            project.getLogger().warn(
                    "coreModClass '{}' declared but neither 'modGroup' nor a project group is set; using as-is",
                    value);
            return value;
        }
        return prefix + "." + value;
    }

    private static Set<String> scanManifests(Set<File> files) {
        var coremodClasses = new LinkedHashSet<String>();
        var seen = new HashSet<Path>();
        for (var file : files) {
            if (!seen.add(file.toPath())) {
                continue;
            }
            var manifest = readManifest(file);
            if (manifest == null) {
                continue;
            }
            var corePlugin = manifest.getMainAttributes().getValue("FMLCorePlugin");
            if (corePlugin != null && !corePlugin.isBlank()) {
                coremodClasses.add(corePlugin.trim());
            }
        }
        return coremodClasses;
    }

    private static Manifest readManifest(File file) {
        try {
            if (file.isDirectory()) {
                var mf = file.toPath().resolve("META-INF").resolve("MANIFEST.MF");
                if (!Files.exists(mf)) {
                    return null;
                }
                try (var in = Files.newInputStream(mf)) {
                    return new Manifest(in);
                }
            } else if (file.getName().endsWith(".jar")) {
                try (var jar = new JarFile(file)) {
                    return jar.getManifest();
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    static class McpHooks implements McpToolchainHooks {
        @Override
        public void configureRuntimeNatives(Configuration configuration, DependencyFactory dependencyFactory, String minecraftVersion) {
            Lwjgl2Natives.configureRuntime(configuration, dependencyFactory, minecraftVersion);
        }

        @Override
        public void configureNativeLibraries(Configuration nativeLibraries, DependencyFactory dependencyFactory, String minecraftVersion) {
            Lwjgl2Natives.configure(nativeLibraries, dependencyFactory, minecraftVersion);
        }

        @Override
        public boolean usesLegacyAppleLwjgl2(String minecraftVersion) {
            return Lwjgl2Natives.shouldUseAppleNativeReplacement(
                    minecraftVersion, System.getProperty("os.name"), System.getProperty("os.arch"));
        }

        @Override
        public boolean needsLegacyForgeSplashDisabled(String minecraftVersion) {
            return "1.12.2".equals(minecraftVersion);
        }
    }
}
