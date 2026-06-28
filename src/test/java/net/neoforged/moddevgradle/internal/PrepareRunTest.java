package net.neoforged.moddevgradle.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import net.neoforged.moddevgradle.internal.utils.VersionCapabilitiesInternal;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.event.Level;

class PrepareRunTest {
    @TempDir
    Path tempDir;

    @Test
    void writesInterpolatedUserdevEnvironment() throws Exception {
        var project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build();
        Files.createDirectories(tempDir.resolve("build"));
        var configJson = tempDir.resolve("config.json");
        Files.writeString(configJson, """
                {
                  "runs": {
                    "client": {
                      "main": "net.minecraftforge.legacydev.MainClient",
                      "args": [],
                      "jvmArgs": [],
                      "props": {},
                      "env": {
                        "MCP_TO_SRG": "{mcp_to_srg}",
                        "mainClass": "net.minecraft.launchwrapper.Launch",
                        "MCP_MAPPINGS": "{mcp_mappings}",
                        "assetIndex": "{asset_index}",
                        "assetDirectory": "{assets_root}",
                        "nativesDirectory": "{natives}",
                        "MC_VERSION": "${MC_VERSION}"
                      }
                    }
                  }
                }
                """, StandardCharsets.UTF_8);

        var assets = tempDir.resolve("assets");
        var assetProperties = tempDir.resolve("assets.properties");
        Files.writeString(assetProperties, """
                asset_index=1.12
                assets_root=%s
                """.formatted(assets), StandardCharsets.ISO_8859_1);

        var task = project.getTasks().register("prepareClientRun", PrepareRun.class).get();
        task.getGameDirectory().set(project.getLayout().getProjectDirectory().dir("run"));
        task.getVmArgsFile().set(project.getLayout().getBuildDirectory().file("runVmArgs.txt"));
        task.getProgramArgsFile().set(project.getLayout().getBuildDirectory().file("runProgramArgs.txt"));
        task.getEnvironmentFile().set(project.getLayout().getBuildDirectory().file("runEnvironment.properties"));
        task.getAssetProperties().set(assetProperties.toFile());
        task.getRunTypeTemplatesSource().from(configJson.toFile());
        task.getRunType().set("client");
        task.getSystemProperties().set(Map.of());
        task.getJvmArguments().set(java.util.List.of());
        task.getProgramArguments().set(java.util.List.of());
        task.getUserEnvironment().set(Map.of("MC_VERSION", "override"));
        task.getRunTemplateReplacements().set(Map.of(
                "mcp_to_srg", tempDir.resolve("named-to-intermediary.srg").toString(),
                "mcp_mappings", tempDir.resolve("mcp-csv.zip").toString()));
        task.getGameLogLevel().set(Level.INFO);
        task.getVersionCapabilities().set(VersionCapabilitiesInternal.ofMinecraftVersion("1.12.2"));

        task.prepareRun();

        assertThat(RunUtils.loadEnvironmentFile(task.getEnvironmentFile().get().getAsFile()))
                .containsEntry("MCP_TO_SRG", tempDir.resolve("named-to-intermediary.srg").toString())
                .containsEntry("MCP_MAPPINGS", tempDir.resolve("mcp-csv.zip").toString())
                .containsEntry("assetIndex", "1.12")
                .containsEntry("assetDirectory", assets.toString())
                .containsEntry("nativesDirectory", task.getGameDirectory().get().dir("natives").getAsFile().getAbsolutePath())
                .containsEntry("mainClass", "net.minecraft.launchwrapper.Launch")
                .containsEntry("MC_VERSION", "override");
    }

    @Test
    void treatsMissingLegacyRunTemplateCollectionsAsEmpty() throws Exception {
        var project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build();
        Files.createDirectories(tempDir.resolve("build"));
        var configJson = tempDir.resolve("config.json");
        Files.writeString(configJson, """
                {
                  "runs": {
                    "client": {
                      "main": "net.minecraftforge.legacydev.MainClient",
                      "env": {
                        "assetIndex": "{asset_index}",
                        "MC_VERSION": "${MC_VERSION}"
                      }
                    }
                  }
                }
                """, StandardCharsets.UTF_8);

        var assetProperties = tempDir.resolve("assets.properties");
        Files.writeString(assetProperties, """
                asset_index=1.12
                assets_root=%s
                """.formatted(tempDir.resolve("assets")), StandardCharsets.ISO_8859_1);

        var task = project.getTasks().register("prepareClientRun", PrepareRun.class).get();
        task.getGameDirectory().set(project.getLayout().getProjectDirectory().dir("run"));
        task.getVmArgsFile().set(project.getLayout().getBuildDirectory().file("runVmArgs.txt"));
        task.getProgramArgsFile().set(project.getLayout().getBuildDirectory().file("runProgramArgs.txt"));
        task.getEnvironmentFile().set(project.getLayout().getBuildDirectory().file("runEnvironment.properties"));
        task.getAssetProperties().set(assetProperties.toFile());
        task.getRunTypeTemplatesSource().from(configJson.toFile());
        task.getRunType().set("client");
        task.getSystemProperties().set(Map.of());
        task.getJvmArguments().set(java.util.List.of());
        task.getProgramArguments().set(java.util.List.of());
        task.getUserEnvironment().set(Map.of());
        task.getRunTemplateReplacements().set(Map.of());
        task.getGameLogLevel().set(Level.INFO);
        task.getVersionCapabilities().set(VersionCapabilitiesInternal.ofMinecraftVersion("1.12.2"));

        task.prepareRun();

        assertThat(Files.readAllLines(task.getVmArgsFile().get().getAsFile().toPath()))
                .doesNotContainNull();
        assertThat(Files.readString(task.getProgramArgsFile().get().getAsFile().toPath()))
                .contains("net.minecraftforge.legacydev.MainClient");
        assertThat(RunUtils.loadEnvironmentFile(task.getEnvironmentFile().get().getAsFile()))
                .containsEntry("assetIndex", "1.12")
                .containsEntry("MC_VERSION", "1.12.2");
    }

    @Test
    void disablesLegacyForgeSplashOnMacOsForForge1122ClientRuns() throws Exception {
        var previousOsName = System.getProperty("os.name");
        System.setProperty("os.name", "Mac OS X");
        try {
            var project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build();
            Files.createDirectories(tempDir.resolve("build"));
            var configJson = tempDir.resolve("config.json");
            Files.writeString(configJson, """
                    {
                      "runs": {
                        "client": {
                          "main": "net.minecraftforge.legacydev.MainClient",
                          "env": {
                            "assetIndex": "{asset_index}",
                            "assetDirectory": "{assets_root}"
                          }
                        }
                      }
                    }
                    """, StandardCharsets.UTF_8);

            var assetProperties = tempDir.resolve("assets.properties");
            Files.writeString(assetProperties, """
                    asset_index=1.12
                    assets_root=%s
                    """.formatted(tempDir.resolve("assets")), StandardCharsets.ISO_8859_1);

            var task = project.getTasks().register("prepareClientRun", PrepareRun.class).get();
            task.getGameDirectory().set(project.getLayout().getProjectDirectory().dir("run"));
            task.getVmArgsFile().set(project.getLayout().getBuildDirectory().file("runVmArgs.txt"));
            task.getProgramArgsFile().set(project.getLayout().getBuildDirectory().file("runProgramArgs.txt"));
            task.getEnvironmentFile().set(project.getLayout().getBuildDirectory().file("runEnvironment.properties"));
            task.getAssetProperties().set(assetProperties.toFile());
            task.getRunTypeTemplatesSource().from(configJson.toFile());
            task.getRunType().set("client");
            task.getSystemProperties().set(Map.of());
            task.getJvmArguments().set(java.util.List.of());
            task.getProgramArguments().set(java.util.List.of());
            task.getUserEnvironment().set(Map.of());
            task.getRunTemplateReplacements().set(Map.of());
            task.getGameLogLevel().set(Level.INFO);
            task.getVersionCapabilities().set(VersionCapabilitiesInternal.ofMinecraftVersion("1.12.2"));

            task.prepareRun();

            var splashProperties = new Properties();
            try (var reader = Files.newBufferedReader(tempDir.resolve("run/config/splash.properties"), StandardCharsets.UTF_8)) {
                splashProperties.load(reader);
            }
            assertThat(splashProperties)
                    .containsEntry("enabled", "false");
        } finally {
            System.setProperty("os.name", previousOsName);
        }
    }

    @Test
    void treatsGameDirectoryAsAnInputBecauseItIsWrittenToEnvironmentFile() throws Exception {
        var project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build();
        var task = project.getTasks().register("prepareClientRun", PrepareRun.class).get();
        task.getGameDirectory().set(project.getLayout().getProjectDirectory().dir("run"));

        var inputProperties = task.getInputs().getProperties();

        assertThat(inputProperties)
                .containsKey("gameDirectoryPath");
        assertThat(inputProperties.get("gameDirectoryPath"))
                .isNotNull();
    }
}
