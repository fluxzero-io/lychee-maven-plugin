package nl.flux.lychee.maven;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LycheeCheckMojoOutputTest {

    @Test
    void normalizeArgsForClickableOutputAddsCompactPlainModeWhenMissing() {
        List<String> args = LycheeCheckMojo.normalizeArgsForClickableOutput(List.of("--verbose"));

        assertEquals(List.of("--verbose", "--format", "compact", "--mode", "plain", "--no-progress"), args);
    }

    @Test
    void normalizeArgsForClickableOutputReplacesExistingFormatAndModeFlags() {
        List<String> args = LycheeCheckMojo.normalizeArgsForClickableOutput(
                List.of("--verbose", "--format", "markdown", "-f=json", "--mode", "emoji", "--no-progress"));

        assertEquals(List.of("--verbose", "--no-progress", "--format", "compact", "--mode", "plain"), args);
    }

    @Test
    void formatLycheeOutputForConsoleCombinesSourceHeaderAndLocation() {
        Path baseDirectory = Path.of("/workspace/project");
        String lycheeOutput = """
                Issues found in 1 input. Find details below.
                
                [docs/readme.md]:
                [ERROR] https://example.invalid/broken-link (at 3:17) | Not found
                
                1 Total
                """;

        List<LycheeCheckMojo.LycheeOutputLine> lines =
                LycheeCheckMojo.formatLycheeOutputForConsole(lycheeOutput, baseDirectory);

        assertEquals(
                new LycheeCheckMojo.LycheeOutputLine(
                        "/workspace/project/docs/readme.md:3:17: [ERROR] https://example.invalid/broken-link (at 3:17) | Not found",
                        true),
                lines.get(3));
    }

    @Test
    void formatLycheeOutputForConsoleKeepsAbsoluteSourcePaths() {
        Path baseDirectory = Path.of("/workspace/project");
        String lycheeOutput = """
                [/tmp/docs/readme.md]:
                [ERROR] https://example.invalid/broken-link (at 7:5) | Not found
                """;

        List<LycheeCheckMojo.LycheeOutputLine> lines =
                LycheeCheckMojo.formatLycheeOutputForConsole(lycheeOutput, baseDirectory);

        assertEquals(
                new LycheeCheckMojo.LycheeOutputLine(
                        "/tmp/docs/readme.md:7:5: [ERROR] https://example.invalid/broken-link (at 7:5) | Not found",
                        true),
                lines.get(1));
    }

    @Test
    void formatLycheeOutputForConsoleLeavesLinesWithoutLocationUntouched() {
        List<LycheeCheckMojo.LycheeOutputLine> lines =
                LycheeCheckMojo.formatLycheeOutputForConsole("1 Total", Path.of("/workspace/project"));

        assertEquals(List.of(new LycheeCheckMojo.LycheeOutputLine("1 Total", false)), lines);
    }
}
