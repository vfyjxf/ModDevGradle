package net.neoforged.moddevgradle.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CreateLaunchScriptTaskTest {
    @TempDir
    Path tempDir;

    @Test
    void writesWindowsEnvironmentVariablesWithCmdSafeSetSyntax() throws Exception {
        Method method = CreateLaunchScriptTask.class.getDeclaredMethod("writeWindowsEnvironmentVariable", Map.Entry.class);
        method.setAccessible(true);

        var escaped = (String) method.invoke(null, Map.entry("MCP_MAPPINGS", "C:\\Users\\A B\\mappings & data.zip"));

        assertThat(escaped)
                .isEqualTo("set \"MCP_MAPPINGS=C:\\Users\\A B\\mappings ^& data.zip\"");
    }

    @Test
    void expandsJvmArgFilesForJava8CompatibleStandaloneLaunchScripts() throws Exception {
        var classpathArgs = tempDir.resolve("classpath.txt");
        var vmArgs = tempDir.resolve("vmargs.txt");
        var programArgs = tempDir.resolve("programargs.txt");
        Files.writeString(classpathArgs, "-classpath\n\"/tmp/libs/a.jar:/tmp/libs/b jar.jar\"\n");
        Files.writeString(vmArgs, "-XstartOnFirstThread\n-Dexample=value\n");
        Files.writeString(programArgs, "# Main Class\nnet.minecraftforge.legacydev.MainClient\n");

        var method = CreateLaunchScriptTask.class.getDeclaredMethod(
                "createJavaCommand",
                String.class,
                java.io.File.class,
                java.io.File.class,
                String.class,
                java.io.File.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        var command = (java.util.List<String>) method.invoke(
                null,
                "/usr/bin/java",
                classpathArgs.toFile(),
                vmArgs.toFile(),
                "-Dfml.modFolders=mymod%%classes",
                programArgs.toFile());

        assertThat(command)
                .containsExactly(
                        "/usr/bin/java",
                        "-classpath",
                        "/tmp/libs/a.jar:/tmp/libs/b jar.jar",
                        "-XstartOnFirstThread",
                        "-Dexample=value",
                        "-Dfml.modFolders=mymod%%classes",
                        RunUtils.DEV_LAUNCH_MAIN_CLASS,
                        "net.minecraftforge.legacydev.MainClient")
                .noneMatch(argument -> argument.startsWith("@"));
    }
}
