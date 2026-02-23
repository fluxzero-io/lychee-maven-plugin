package nl.flux.lychee.maven;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LycheeChecksumVerificationTest {

    @Test
    void officialGithubReleaseDownloadIsDetected() {
        assertTrue(LycheeCheckMojo.isOfficialGithubReleaseDownload(
                URI.create("https://github.com/lycheeverse/lychee/releases/download/lychee-v0.23.0/lychee-x86_64-unknown-linux-gnu.tar.gz")));
        assertFalse(LycheeCheckMojo.isOfficialGithubReleaseDownload(
                URI.create("https://example.com/releases/lychee-v0.23.0/lychee.tar.gz")));
    }

    @Test
    void findsSha256DigestForExpectedAsset() {
        String json = """
                {
                  "assets": [
                    {
                      "name": "lychee-x86_64-unknown-linux-gnu.tar.gz",
                      "digest": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                    },
                    {
                      "name": "lychee-arm64-macos.tar.gz",
                      "digest": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                    }
                  ]
                }
                """;

        String digest = LycheeCheckMojo.findSha256DigestForAsset(
                json,
                "lychee-arm64-macos.tar.gz");

        assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", digest);
    }

    @Test
    void returnsNullWhenDigestMissingOrAssetNotFound() {
        String json = """
                {
                  "assets": [
                    {
                      "name": "lychee-x86_64-unknown-linux-gnu.tar.gz"
                    }
                  ]
                }
                """;

        assertNull(LycheeCheckMojo.findSha256DigestForAsset(
                json,
                "lychee-x86_64-unknown-linux-gnu.tar.gz"));
        assertNull(LycheeCheckMojo.findSha256DigestForAsset(
                json,
                "lychee-arm64-macos.tar.gz"));
    }

    @Test
    void computesSha256ForFileContent() throws Exception {
        Path file = Files.createTempFile("lychee-checksum-test", ".txt");
        try {
            Files.writeString(file, "hello");
            String digest = LycheeCheckMojo.computeSha256(file);
            assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", digest);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void computeSha256ThrowsForMissingFile() {
        Path missing = Path.of("target/this-file-should-not-exist.bin");
        assertThrows(java.io.IOException.class, () -> LycheeCheckMojo.computeSha256(missing));
    }

    @Test
    void normalizeSha256AcceptsPlainAndPrefixedValues() throws Exception {
        String value = "ABCDEFABCDEFABCDEFABCDEFABCDEFABCDEFABCDEFABCDEFABCDEFABCDEFABCD";
        assertEquals(value.toLowerCase(), LycheeCheckMojo.normalizeSha256(value));
        assertEquals(value.toLowerCase(), LycheeCheckMojo.normalizeSha256("sha256:" + value));
        assertNull(LycheeCheckMojo.normalizeSha256(null));
        assertNull(LycheeCheckMojo.normalizeSha256("   "));
    }

    @Test
    void normalizeSha256RejectsInvalidValues() {
        assertThrows(MojoExecutionException.class, () -> LycheeCheckMojo.normalizeSha256("not-a-sha"));
        assertThrows(MojoExecutionException.class, () -> LycheeCheckMojo.normalizeSha256("sha256:abc123"));
    }
}
