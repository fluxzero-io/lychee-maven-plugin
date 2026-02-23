package nl.flux.lychee.maven;

import java.util.Locale;
import java.util.Objects;

final class LycheePlatform {

    private LycheePlatform() {
    }

    static String resolveAssetName(String osName, String osArch, String linuxVariant) {
        String normalizedOs = normalizeOs(osName);
        String normalizedArch = normalizeArch(osArch);
        String normalizedLinuxVariant = linuxVariant == null ? "gnu" : linuxVariant.toLowerCase(Locale.ROOT);

        if ("windows".equals(normalizedOs)) {
            if (!"x86_64".equals(normalizedArch)) {
                throw new IllegalStateException("Lychee release assets currently support only x86_64 on Windows.");
            }
            return "lychee-x86_64-windows.exe";
        }

        if ("macos".equals(normalizedOs)) {
            if ("aarch64".equals(normalizedArch) || "arm64".equals(normalizedArch)) {
                return "lychee-arm64-macos.tar.gz";
            }
            throw new IllegalStateException("Lychee release assets currently support only arm64 on macOS.");
        }

        if ("linux".equals(normalizedOs)) {
            if (!Objects.equals(normalizedLinuxVariant, "gnu") && !Objects.equals(normalizedLinuxVariant, "musl")) {
                throw new IllegalArgumentException("linuxVariant must be either 'gnu' or 'musl'.");
            }
            return switch (normalizedArch) {
                case "x86_64" -> "lychee-x86_64-unknown-linux-" + normalizedLinuxVariant + ".tar.gz";
                case "aarch64" -> "lychee-aarch64-unknown-linux-" + normalizedLinuxVariant + ".tar.gz";
                case "i686" -> {
                    if (!Objects.equals(normalizedLinuxVariant, "gnu")) {
                        throw new IllegalStateException("i686 linux builds are only available for gnu.");
                    }
                    yield "lychee-i686-unknown-linux-gnu.tar.gz";
                }
                case "armv7" -> {
                    if (!Objects.equals(normalizedLinuxVariant, "gnu")) {
                        throw new IllegalStateException("armv7 linux builds are only available for gnu.");
                    }
                    yield "lychee-armv7-unknown-linux-gnueabihf.tar.gz";
                }
                case "armv6" -> {
                    if (!Objects.equals(normalizedLinuxVariant, "musl")) {
                        throw new IllegalStateException("armv6 linux builds are only available for musl.");
                    }
                    yield "lychee-arm-unknown-linux-musleabi.tar.gz";
                }
                case "armv7-musl" -> {
                    if (!Objects.equals(normalizedLinuxVariant, "musl")) {
                        throw new IllegalStateException("armv7 musl asset requires linuxVariant=musl.");
                    }
                    yield "lychee-arm-unknown-linux-musleabihf.tar.gz";
                }
                default -> throw new IllegalStateException("Unsupported Linux architecture for lychee: " + osArch);
            };
        }

        throw new IllegalStateException("Unsupported operating system for lychee: " + osName);
    }

    static boolean isWindows(String osName) {
        return "windows".equals(normalizeOs(osName));
    }

    private static String normalizeOs(String osName) {
        String value = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (value.contains("win")) {
            return "windows";
        }
        if (value.contains("mac") || value.contains("darwin")) {
            return "macos";
        }
        if (value.contains("linux")) {
            return "linux";
        }
        return value;
    }

    private static String normalizeArch(String osArch) {
        String value = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "amd64", "x86_64" -> "x86_64";
            case "x86", "i386", "i486", "i586", "i686" -> "i686";
            case "aarch64", "arm64" -> "aarch64";
            case "armv7", "armv7l" -> "armv7";
            case "armv6", "armv6l" -> "armv6";
            case "arm" -> "armv7-musl";
            default -> value;
        };
    }
}
