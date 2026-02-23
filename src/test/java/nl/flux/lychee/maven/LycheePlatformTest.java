package nl.flux.lychee.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LycheePlatformTest {

    @Test
    void resolvesLinuxX64Gnu() {
        assertEquals(
                "lychee-x86_64-unknown-linux-gnu.tar.gz",
                LycheePlatform.resolveAssetName("Linux", "amd64", "gnu"));
    }

    @Test
    void resolvesMacArm64() {
        assertEquals(
                "lychee-arm64-macos.tar.gz",
                LycheePlatform.resolveAssetName("Mac OS X", "aarch64", "gnu"));
    }

    @Test
    void resolvesWindowsX64() {
        assertEquals(
                "lychee-x86_64-windows.exe",
                LycheePlatform.resolveAssetName("Windows 11", "x86_64", "gnu"));
    }

    @Test
    void failsOnUnsupportedMacArch() {
        assertThrows(
                IllegalStateException.class,
                () -> LycheePlatform.resolveAssetName("Mac OS X", "x86_64", "gnu"));
    }
}
