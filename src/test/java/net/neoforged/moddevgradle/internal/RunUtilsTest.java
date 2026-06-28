package net.neoforged.moddevgradle.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.file.Path;
import java.util.List;

class RunUtilsTest {
    @ParameterizedTest
    @CsvSource(textBlock = """
            ""|\\"\\"
            |
            a b|"a b"
            a" b|"a\\" b"
            a\\b|a\\\\b
            """, delimiter = '|')
    public void testEscape(String unescaped, String escaped) {
        escaped = escaped == null ? "" : escaped;
        unescaped = unescaped == null ? "" : unescaped;

        assertEquals(escaped, RunUtils.escapeJvmArg(unescaped));
    }

    @Test
    void readsPreparedArgumentFile(@TempDir Path tempDir) throws Exception {
        var argFile = tempDir.resolve("args.txt");
        Files.writeString(argFile, """
                # comment
                -Done=1
                "two words"
                escaped\\\\path

                """, StandardCharsets.UTF_8);

        assertEquals(List.of("-Done=1", "two words", "escaped\\path"), RunUtils.readArgFile(argFile.toFile()));
    }
}
