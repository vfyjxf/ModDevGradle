package net.neoforged.moddevgradle.legacyforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;
import net.neoforged.moddevgradle.AbstractProjectBuilderTest;
import net.neoforged.moddevgradle.internal.ExtractNatives;
import net.neoforged.moddevgradle.internal.ModDevRunWorkflow;
import net.neoforged.moddevgradle.internal.utils.ExtensionUtils;
import net.neoforged.moddevgradle.legacyforge.dsl.LegacyForgeExtension;
import net.neoforged.moddevgradle.legacyforge.internal.LegacyForgeModDevPlugin;
import net.neoforged.nfrtgradle.CreateMinecraftArtifacts;
import org.gradle.api.InvalidUserCodeException;
import org.gradle.api.Task;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

public class LegacyModDevPluginTest extends AbstractProjectBuilderTest {
    private static final String[] MODDING_COMPILE_DEPENDENCIES = {
            "build/moddev/artifacts/forge-1.2.3.jar",
            "net.minecraftforge:forge:1.2.3[net.neoforged:neoforge-dependencies]"
    };
    private static final String[] MODDING_RUNTIME_ONLY_DEPENDENCIES = {
            "build/moddev/artifacts/client-extra-1.2.3.jar",
            "build/moddev/artifacts/intermediateToNamed.zip",
    };
    public static final String VERSION = "1.2.3";

    private final LegacyForgeExtension extension;
    private final SourceSet mainSourceSet;
    private final SourceSet testSourceSet;

    public LegacyModDevPluginTest() {
        project = ProjectBuilder.builder().build();
        project.getPlugins().apply(LegacyForgeModDevPlugin.class);

        extension = ExtensionUtils.getExtension(project, "legacyForge", LegacyForgeExtension.class);

        var sourceSets = ExtensionUtils.getSourceSets(project);
        mainSourceSet = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        testSourceSet = sourceSets.getByName(SourceSet.TEST_SOURCE_SET_NAME);

        // Set the Java version to the currently running Java to make it use that
        var java = ExtensionUtils.getExtension(project, "java", JavaPluginExtension.class);
        java.getToolchain().getLanguageVersion().set(JavaLanguageVersion.current());
    }

    @Test
    void testModdingCannotBeEnabledTwice() {
        extension.setVersion(VERSION);
        var e = assertThrows(InvalidUserCodeException.class, () -> extension.setVersion(VERSION));
        assertThat(e).hasMessage("You cannot enable modding in the same project twice.");
    }

    @Test
    void testEnableVanillaOnlyMode() {
        extension.setMcpVersion("1.17.1");

        assertThatDependencies(mainSourceSet.getCompileClasspathConfigurationName())
                .contains(
                        "build/moddev/artifacts/vanilla-1.17.1.jar",
                        "de.oceanlabs.mcp:mcp_config:1.17.1[net.neoforged:neoform-dependencies]");
        assertThatDependencies(mainSourceSet.getCompileClasspathConfigurationName())
                .doesNotContain(
                        "build/moddev/artifacts/vanilla-1.17.1-client-extra-aka-minecraft-resources.jar",
                        "build/moddev/artifacts/intermediateToNamed.zip");
        assertThatDependencies(mainSourceSet.getRuntimeClasspathConfigurationName())
                .contains(
                        "build/moddev/artifacts/vanilla-1.17.1.jar",
                        "build/moddev/artifacts/vanilla-1.17.1-client-extra-aka-minecraft-resources.jar",
                        "de.oceanlabs.mcp:mcp_config:1.17.1[net.neoforged:neoform-dependencies]",
                        "build/moddev/artifacts/intermediateToNamed.zip");
        assertEquals("1.17.1", extension.getMcpVersion());
    }

    @Test
    void testGetMcpVersionThrowsBeforeEnabling() {
        assertThrows(InvalidUserCodeException.class, extension::getMcpVersion);
    }

    @Test
    void testForge1122UsesUserdev3ForNeoFormRuntime() {
        project.getExtensions().getByName("neoFormRuntime");
        project.getExtensions().configure(net.neoforged.nfrtgradle.NeoFormRuntimeExtension.class, extension -> extension.getVersion().set("2.0.19-legacy"));

        extension.setVersion("1.12.2-14.23.5.2860");

        var createArtifacts = project.getTasks().named("createMinecraftArtifacts", CreateMinecraftArtifacts.class).get();
        assertEquals("net.minecraftforge:forge:1.12.2-14.23.5.2860:userdev3", createArtifacts.getNeoForgeArtifact().get());
        assertThat(createArtifacts.getLegacyMcpMappings().isPresent()).isFalse();
        assertEquals("1.12.2", extension.getMinecraftVersion());
    }

    @Test
    void testLegacyRunExtractsNatives() {
        extension.getRuns().create("client").client();
        project.getExtensions().configure(net.neoforged.nfrtgradle.NeoFormRuntimeExtension.class, nfrt -> nfrt.getVersion().set("2.0.19-legacy"));

        extension.setVersion("1.12.2-14.23.5.2860");

        var extractNatives = project.getTasks().named("extractClientNatives", ExtractNatives.class);
        assertThat(project.getTasks().getNames()).contains("extractClientNatives");
        assertThat(project.getTasks().named("prepareClientRun").get().getDependsOn())
                .contains(extractNatives);

        var task = extractNatives.get();
        assertThat(task.getEnabledForRun().get()).isTrue();
        assertThat(task.getNativeLibraries().getFrom()).contains(project.getConfigurations().getByName("clientNativeLibraries"));
    }

    @Test
    void testForge1122DoesNotAddBootstrapLauncherExportsToJava8Run() {
        var run = extension.getRuns().create("client");
        run.client();
        project.getExtensions().configure(net.neoforged.nfrtgradle.NeoFormRuntimeExtension.class, nfrt -> nfrt.getVersion().set("2.0.19-legacy"));

        extension.setVersion("1.12.2-14.23.5.2860");

        assertThat(run.getJvmArguments().get())
                .doesNotContain("--add-exports", "cpw.mods.bootstraplauncher/cpw.mods.bootstraplauncher=ALL-UNNAMED");
    }

    @Test
    void testForge1122IgnoresRuntimePatchDiscrepanciesForPreparedDevJar() {
        var run = extension.getRuns().create("client");
        run.client();
        project.getExtensions().configure(net.neoforged.nfrtgradle.NeoFormRuntimeExtension.class, nfrt -> nfrt.getVersion().set("2.0.19-legacy"));

        extension.setVersion("1.12.2-14.23.5.2860");

        assertThat(run.getSystemProperties().get())
                .containsEntry("fml.ignorePatchDiscrepancies", "true");
    }

    @Test
    void testForge1122PassesSrgToMcpMappingsToLegacyDevLauncher() throws Exception {
        extension.getRuns().create("client").client();
        project.getExtensions().configure(net.neoforged.nfrtgradle.NeoFormRuntimeExtension.class, nfrt -> nfrt.getVersion().set("2.0.19-legacy"));

        extension.setVersion("1.12.2-14.23.5.2860");

        var prepareRun = project.getTasks().named("prepareClientRun").get();
        assertThat(getRunTemplateReplacements(prepareRun))
                .containsEntry("mcp_to_srg", project.file("build/moddev/artifacts/intermediateToNamed.srg").getAbsolutePath());
    }

    @Test
    void testForgeRepositorySupportsJarOnlyLegacyArtifacts() {
        var forgeRepository = (MavenArtifactRepository) project.getRepositories().getByName("MinecraftForge");

        assertThat(forgeRepository.getMetadataSources().isMavenPomEnabled()).isTrue();
        assertThat(forgeRepository.getMetadataSources().isArtifactEnabled()).isTrue();
    }

    @Test
    void testForge1171UsesUserdevForNeoFormRuntime() {
        extension.setVersion("1.17.1-37.1.1");

        var createArtifacts = project.getTasks().named("createMinecraftArtifacts", CreateMinecraftArtifacts.class).get();
        assertEquals("net.minecraftforge:forge:1.17.1-37.1.1:userdev", createArtifacts.getNeoForgeArtifact().get());
        assertThat(createArtifacts.getLegacyMcpMappings().isPresent()).isFalse();
        assertEquals("1.17.1", extension.getMinecraftVersion());
    }

    @Test
    void testLegacyMcpMappingsCanBeConfiguredForNeoFormRuntime() {
        project.getExtensions().configure(net.neoforged.nfrtgradle.NeoFormRuntimeExtension.class, nfrt -> nfrt.getVersion().set("2.0.19-legacy"));

        extension.enable(settings -> {
            settings.setForgeVersion("1.12.2-14.23.5.2860");
            settings.setMcpMappings("de.oceanlabs.mcp:mcp_stable:39-1.12@zip");
        });

        var createArtifacts = project.getTasks().named("createMinecraftArtifacts", CreateMinecraftArtifacts.class).get();
        assertEquals("de.oceanlabs.mcp:mcp_stable:39-1.12@zip", createArtifacts.getLegacyMcpMappings().get());
    }

    @Test
    void testGradleTestTaskLoadsPreparedEnvironmentFile() throws Exception {
        project.getExtensions().configure(net.neoforged.nfrtgradle.NeoFormRuntimeExtension.class, nfrt -> nfrt.getVersion().set("2.0.19-legacy"));
        extension.setVersion("1.12.2-14.23.5.2860");
        var testTask = project.getTasks().named("test", org.gradle.api.tasks.testing.Test.class).get();
        var initialActionCount = testTask.getActions().size();

        ModDevRunWorkflow.get(project).configureTesting(project.provider(() -> null), project.provider(() -> Set.of()));

        assertThat(testTask.getActions()).hasSizeGreaterThan(initialActionCount);

        var environmentFile = project.getLayout().getBuildDirectory()
                .file("moddev/junit/environment.properties")
                .get()
                .getAsFile()
                .toPath();
        Files.createDirectories(environmentFile.getParent());
        Files.writeString(environmentFile, "MCP_TO_SRG=prepared.srg\n", StandardCharsets.ISO_8859_1);

        var loadEnvironment = project.getObjects().newInstance(ModDevRunWorkflow.LoadPreparedTestEnvironment.class);
        loadEnvironment.getEnvironmentFile().set(project.getLayout().getBuildDirectory().file("moddev/junit/environment.properties"));
        loadEnvironment.execute(testTask);

        assertThat(testTask.getEnvironment())
                .containsEntry("MCP_TO_SRG", "prepared.srg");
    }

    @Test
    void testForge1122RequiresLegacyNeoFormRuntimeSupport() {
        var e = assertThrows(InvalidUserCodeException.class, () -> extension.setVersion("1.12.2-14.23.5.2860"));

        assertThat(e).hasMessageContaining("Forge 1.12.2 requires NeoFormRuntime with legacy MCP support");
        assertThat(e).hasMessageContaining("neoForge.neoFormRuntime.version");
        assertThat(e).hasMessageContaining("2.0.19-legacy");
    }

    @Test
    void testEnableForTestSourceSetOnly() {
        extension.enable(settings -> {
            settings.setForgeVersion(VERSION);
            settings.setEnabledSourceSets(Set.of(testSourceSet));
        });

        // Both the compile and runtime classpath of the main source set had no dependencies added
        assertDoesNotContainModdingDependencies(mainSourceSet.getCompileClasspathConfigurationName());
        assertDoesNotContainModdingDependencies(mainSourceSet.getRuntimeClasspathConfigurationName());

        // While the test classpath should have modding dependencies
        assertContainsModdingCompileDependencies(testSourceSet.getCompileClasspathConfigurationName());
        assertContainsModdingRuntimeDependencies(testSourceSet.getRuntimeClasspathConfigurationName());
    }

    @Test
    void testAddModdingDependenciesTo() {
        extension.setVersion(VERSION);

        // Initially, only the main source set should have the dependencies
        assertContainsModdingCompileDependencies(mainSourceSet.getCompileClasspathConfigurationName());
        assertContainsModdingRuntimeDependencies(mainSourceSet.getRuntimeClasspathConfigurationName());
        assertDoesNotContainModdingDependencies(testSourceSet.getCompileClasspathConfigurationName());
        assertDoesNotContainModdingDependencies(testSourceSet.getRuntimeClasspathConfigurationName());

        // Now add it to the test source set too
        extension.addModdingDependenciesTo(testSourceSet);

        assertContainsModdingCompileDependencies(testSourceSet.getCompileClasspathConfigurationName());
        assertContainsModdingRuntimeDependencies(testSourceSet.getRuntimeClasspathConfigurationName());
    }

    @Test
    void testEnableWithoutReobfTask() {
        extension.enable(settings -> {
            settings.setForgeVersion(VERSION);
            settings.setObfuscateJar(false);
        });

        assertNull(project.getTasks().findByName("reobfJar"));
    }

    private void assertDoesNotContainModdingDependencies(String configurationName) {
        assertThatDependencies(configurationName).doesNotContain(MODDING_COMPILE_DEPENDENCIES);
        assertThatDependencies(configurationName).doesNotContain(MODDING_RUNTIME_ONLY_DEPENDENCIES);
    }

    private void assertContainsModdingCompileDependencies(String configurationName) {
        assertThatDependencies(configurationName).contains(MODDING_COMPILE_DEPENDENCIES);
        assertThatDependencies(configurationName).doesNotContain(MODDING_RUNTIME_ONLY_DEPENDENCIES);
    }

    private void assertContainsModdingRuntimeDependencies(String configurationName) {
        var configuration = project.getConfigurations().getByName(configurationName);

        var dependentTasks = configuration.getBuildDependencies().getDependencies(null);
        assertThat(dependentTasks)
                .extracting(Task::getName)
                .containsOnly("createMinecraftArtifacts");

        assertThatDependencies(configurationName).contains(MODDING_COMPILE_DEPENDENCIES);
        assertThatDependencies(configurationName).contains(MODDING_RUNTIME_ONLY_DEPENDENCIES);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> getRunTemplateReplacements(Task task) throws Exception {
        try {
            var replacementsProperty = task.getClass().getMethod("getRunTemplateReplacements").invoke(task);
            return (Map<String, String>) replacementsProperty.getClass().getMethod("get").invoke(replacementsProperty);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception exception) {
                throw exception;
            }
            throw e;
        }
    }
}
