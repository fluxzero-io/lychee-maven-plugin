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
                URI.create("https://github.com/lycheeverse/lychee/releases/download/lychee-v0.24.2/lychee-x86_64-unknown-linux-gnu.tar.gz")));
        assertFalse(LycheeCheckMojo.isOfficialGithubReleaseDownload(
                URI.create("https://example.com/releases/lychee-v0.24.2/lychee.tar.gz")));
    }

    @Test
    void findsSha256DigestInSidecarWithFilename() {
        String sidecar = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb  lychee-aarch64-apple-darwin.tar.gz";

        String digest = LycheeCheckMojo.findSha256DigestInText(sidecar);

        assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", digest);
    }

    @Test
    void findsSha256DigestWithPrefix() {
        String digest = LycheeCheckMojo.findSha256DigestInText(
                "sha256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", digest);
    }

    @Test
    void returnsNullWhenDigestMissing() {
        assertNull(LycheeCheckMojo.findSha256DigestInText("not-a-checksum"));
        assertNull(LycheeCheckMojo.findSha256DigestInText(null));
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
