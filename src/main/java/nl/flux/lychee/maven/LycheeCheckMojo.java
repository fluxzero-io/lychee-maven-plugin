package nl.flux.lychee.maven;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.settings.Proxy;
import org.apache.maven.settings.Server;
import org.apache.maven.shared.model.fileset.FileSet;
import org.codehaus.plexus.util.DirectoryScanner;

@SuppressWarnings({"unused", "MismatchedQueryAndUpdateOfCollection"})
/**
 * Runs lychee against project documentation files selected by configured scan directories.
 */
@Mojo(name = "check", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public class LycheeCheckMojo extends AbstractMojo {

    static final String DEFAULT_LYCHEE_VERSION = "0.23.0";
    static final String OFFICIAL_RELEASE_BASE_URL = "https://github.com/lycheeverse/lychee/releases/download";
    static final String RELEASE_TAG_API_BASE_URL = "https://api.github.com/repos/lycheeverse/lychee/releases/tags";
    static final int CONNECT_TIMEOUT_SECONDS = 30;
    static final int READ_TIMEOUT_SECONDS = 120;
    static final List<String> DEFAULT_INCLUDES = Arrays.asList(
            "**/*.md",
            "**/*.markdown",
            "**/*.adoc",
            "**/*.asciidoc",
            "**/*.rst",
            "**/*.html",
            "**/*.htm");
    static final List<String> DEFAULT_EXCLUDES = Arrays.asList(
            "**/.git/**",
            "**/target/**",
            "**/node_modules/**");

    @Parameter(property = "lychee.skip", defaultValue = "false")
    private boolean skip;

    @Parameter(property = "lychee.version", defaultValue = DEFAULT_LYCHEE_VERSION)
    private String version;

    @Parameter(property = "lychee.linuxVariant", defaultValue = "gnu")
    private String linuxVariant;

    @Parameter(property = "lychee.assetName")
    private String assetName;

    @Parameter(property = "lychee.downloadBaseUrl",
            defaultValue = "https://github.com/lycheeverse/lychee/releases/download")
    private String downloadBaseUrl;

    @Parameter(property = "lychee.downloadServerId")
    private String downloadServerId;

    @Parameter(property = "lychee.failOnError", defaultValue = "true")
    private boolean failOnError;

    @Parameter(property = "lychee.verifyChecksum", defaultValue = "true")
    private boolean verifyChecksum;

    @Parameter(property = "lychee.expectedSha256")
    private String expectedSha256;

    @Parameter(property = "lychee.downloadRetries", defaultValue = "3")
    private int downloadRetries;

    @Parameter(property = "lychee.retryBackoffMillis", defaultValue = "1000")
    private long retryBackoffMillis;

    @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
    private File baseDirectory;

    @Parameter(defaultValue = "${project.build.directory}/lychee", required = true)
    private File installDirectory;

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession session;

    @Parameter
    private List<FileSet> scanDirectories;

    @Parameter
    private List<String> args;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping lychee check.");
            return;
        }

        List<Path> documents = collectDocuments();
        if (documents.isEmpty()) {
            getLog().info("No matching documents found. Nothing to check.");
            return;
        }

        Path lycheeBinary = ensureLycheeBinary();
        int exitCode = runLychee(lycheeBinary, documents);
        if (exitCode != 0 && failOnError) {
            throw new MojoFailureException("lychee reported broken links. Exit code: " + exitCode);
        }
        if (exitCode != 0) {
            getLog().warn("lychee reported broken links. Exit code: " + exitCode);
        }
    }

    private List<Path> collectDocuments() {
        Path baseDirectoryPath = baseDirectory.toPath().toAbsolutePath().normalize();
        List<FileSet> scanConfigs = scanDirectories == null || scanDirectories.isEmpty()
                ? List.of(defaultScanDirectory(baseDirectoryPath))
                : scanDirectories;

        Set<Path> matches = new LinkedHashSet<>();
        for (FileSet scanDirectory : scanConfigs) {
            Path root = (scanDirectory.getDirectory() == null || scanDirectory.getDirectory().isBlank())
                    ? baseDirectoryPath
                    : Paths.get(scanDirectory.getDirectory());
            Path resolvedRoot = root.isAbsolute() ? root : baseDirectoryPath.resolve(root);
            if (!Files.isDirectory(resolvedRoot)) {
                getLog().warn("Scan directory does not exist or is not a directory: " + resolvedRoot);
                continue;
            }

            List<String> includePatterns = scanDirectory.getIncludes() == null || scanDirectory.getIncludes().isEmpty()
                    ? DEFAULT_INCLUDES
                    : scanDirectory.getIncludes();
            List<String> excludePatterns = scanDirectory.getExcludes() == null || scanDirectory.getExcludes().isEmpty()
                    ? DEFAULT_EXCLUDES
                    : scanDirectory.getExcludes();

            DirectoryScanner scanner = new DirectoryScanner();
            scanner.setBasedir(resolvedRoot.toFile());
            scanner.setIncludes(includePatterns.toArray(String[]::new));
            scanner.setExcludes(excludePatterns.toArray(String[]::new));
            scanner.scan();
            for (String relativePath : scanner.getIncludedFiles()) {
                matches.add(resolvedRoot.resolve(relativePath).normalize().toAbsolutePath());
            }
        }

        getLog().info("Found " + matches.size() + " document(s) for lychee.");
        return new ArrayList<>(matches);
    }

    private Path ensureLycheeBinary() throws MojoExecutionException {
        try {
            String resolvedAsset = assetName == null || assetName.isBlank()
                    ? LycheePlatform.resolveAssetName(System.getProperty("os.name"), System.getProperty("os.arch"), linuxVariant)
                    : assetName;
            Path targetDir = installDirectory.toPath().resolve("lychee-v" + version);
            Path binary = targetDir.resolve(binaryFileName());

            if (Files.isExecutable(binary)) {
                getLog().debug("Using existing lychee binary: " + binary);
                return binary;
            }

            Files.createDirectories(targetDir);
            URI downloadUri = URI.create(downloadBaseUrl + "/lychee-v" + version + "/" + resolvedAsset);
            getLog().info("Downloading lychee from " + downloadUri);

            //noinspection resource
            HttpClient client = createHttpClient(downloadUri);
            HttpRequest request = HttpRequest.newBuilder(downloadUri)
                    .timeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
                    .header("Accept", "application/octet-stream")
                    .GET()
                    .build();

            Path downloaded = targetDir.resolve(resolvedAsset);
            HttpResponse<Path> response = sendWithRetry(
                    client,
                    request,
                    HttpResponse.BodyHandlers.ofFile(downloaded),
                    "lychee binary download",
                    statusCode -> statusCode >= 500);
            if (response.statusCode() >= 400) {
                throw new MojoExecutionException(
                        "Failed to download lychee binary. HTTP " + response.statusCode() + " from " + downloadUri);
            }
            verifyDownloadedAssetChecksumIfSupported(downloadUri, resolvedAsset, version, downloaded, targetDir);

            if (resolvedAsset.endsWith(".tar.gz")) {
                extractTarGz(downloaded, targetDir, binary);
            } else if (resolvedAsset.endsWith(".zip")) {
                extractZip(downloaded, targetDir, binary);
            } else if (resolvedAsset.endsWith(".exe")) {
                Files.copy(downloaded, binary, StandardCopyOption.REPLACE_EXISTING);
            } else {
                throw new MojoExecutionException("Unsupported archive format for lychee asset: " + resolvedAsset);
            }

            ensureExecutable(binary);
            return binary;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Failed to download or prepare lychee binary", e);
        } catch (IOException | IllegalArgumentException e) {
            throw new MojoExecutionException("Failed to download or prepare lychee binary", e);
        }
    }

    private void verifyDownloadedAssetChecksumIfSupported(
            URI downloadUri, String resolvedAsset, String resolvedVersion, Path downloadedAsset, Path targetDir)
            throws IOException, InterruptedException, MojoExecutionException {
        if (!verifyChecksum) {
            getLog().warn("Skipping SHA-256 verification because lychee.verifyChecksum=false.");
            return;
        }

        String expectedSha256 = resolveExpectedSha256(downloadUri, resolvedAsset, resolvedVersion, targetDir);
        if (expectedSha256 == null) {
            getLog().warn("Skipping SHA-256 verification because no expected digest is available.");
            return;
        }

        String actualSha256 = computeSha256(downloadedAsset);
        if (!expectedSha256.equalsIgnoreCase(actualSha256)) {
            throw new MojoExecutionException(
                    "SHA-256 verification failed for downloaded lychee asset '" + resolvedAsset + "'.");
        }
        getLog().info("Verified SHA-256 for downloaded lychee asset.");
    }

    private String resolveExpectedSha256(URI downloadUri, String resolvedAsset, String resolvedVersion, Path targetDir)
            throws IOException, InterruptedException, MojoExecutionException {
        String normalizedConfigured = normalizeSha256(expectedSha256);
        if (normalizedConfigured != null) {
            return normalizedConfigured;
        }

        if (!isOfficialGithubReleaseDownload(downloadUri)) {
            getLog().warn("Skipping SHA-256 verification for non-official download URL: " + downloadUri);
            return null;
        }

        Path metadataCache = targetDir.resolve("release-metadata.json");
        if (Files.isRegularFile(metadataCache)) {
            String cachedJson = Files.readString(metadataCache, StandardCharsets.UTF_8);
            String cachedDigest = findSha256DigestForAsset(cachedJson, resolvedAsset);
            if (cachedDigest != null) {
                getLog().debug("Using cached release metadata for SHA-256 verification.");
                return cachedDigest;
            }
        }

        URI releaseMetadataUri = URI.create(RELEASE_TAG_API_BASE_URL + "/lychee-v" + resolvedVersion);
        //noinspection resource
        HttpClient client = createHttpClient(releaseMetadataUri);
        HttpRequest metadataRequest = HttpRequest.newBuilder(releaseMetadataUri)
                .timeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build();
        HttpResponse<String> metadataResponse = sendWithRetry(
                client,
                metadataRequest,
                HttpResponse.BodyHandlers.ofString(),
                "release metadata download",
                statusCode -> statusCode >= 500);
        if (metadataResponse.statusCode() >= 400) {
            throw new MojoExecutionException(
                    "Failed to fetch release metadata for SHA-256 verification. HTTP "
                            + metadataResponse.statusCode() + " from " + releaseMetadataUri);
        }

        Files.writeString(metadataCache, metadataResponse.body(), StandardCharsets.UTF_8);
        return findSha256DigestForAsset(metadataResponse.body(), resolvedAsset);
    }

    static boolean isOfficialGithubReleaseDownload(URI uri) {
        if (uri == null) {
            return false;
        }
        String normalized = uri.toString();
        return normalized.startsWith(OFFICIAL_RELEASE_BASE_URL + "/");
    }

    static String findSha256DigestForAsset(String releaseMetadataJson, String expectedAssetName) {
        if (releaseMetadataJson == null || expectedAssetName == null || expectedAssetName.isBlank()) {
            return null;
        }

        JsonObject root = JsonParser.parseString(releaseMetadataJson).getAsJsonObject();
        JsonArray assets = root.getAsJsonArray("assets");
        if (assets == null) {
            return null;
        }

        for (JsonElement element : assets) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject asset = element.getAsJsonObject();
            JsonElement nameElement = asset.get("name");
            if (nameElement == null || !expectedAssetName.equals(nameElement.getAsString())) {
                continue;
            }
            JsonElement digestElement = asset.get("digest");
            if (digestElement == null) {
                return null;
            }
            String digest = digestElement.getAsString();
            if (!digest.startsWith("sha256:")) {
                return null;
            }
            return digest.substring("sha256:".length());
        }
        return null;
    }

    static String normalizeSha256(String value) throws MojoExecutionException {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.startsWith("sha256:")) {
            normalized = normalized.substring("sha256:".length());
        }
        normalized = normalized.toLowerCase();
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new MojoExecutionException(
                    "Invalid lychee.expectedSha256 value. Expected 64 hex characters (optionally prefixed with 'sha256:').");
        }
        return normalized;
    }

    static String computeSha256(Path file) throws IOException, MojoExecutionException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new MojoExecutionException("SHA-256 is not available in this JVM.", e);
        }

        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }

        byte[] value = digest.digest();
        StringBuilder hex = new StringBuilder(value.length * 2);
        for (byte b : value) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private <T> HttpResponse<T> sendWithRetry(
            HttpClient client,
            HttpRequest request,
            HttpResponse.BodyHandler<T> bodyHandler,
            String operation,
            IntPredicate retryableStatusCode)
            throws IOException, InterruptedException {
        int attempts = Math.max(1, downloadRetries);
        long backoffMillis = Math.max(0L, retryBackoffMillis);
        IOException lastException = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpResponse<T> response = client.send(request, bodyHandler);
                if (retryableStatusCode.test(response.statusCode()) && attempt < attempts) {
                    getLog().warn(operation + " failed with HTTP " + response.statusCode()
                            + "; retrying (" + attempt + "/" + attempts + ").");
                    sleepBackoff(backoffMillis);
                    continue;
                }
                return response;
            } catch (IOException e) {
                lastException = e;
                if (attempt >= attempts) {
                    throw e;
                }
                getLog().warn(operation + " failed (" + e.getClass().getSimpleName()
                        + "); retrying (" + attempt + "/" + attempts + ").");
                sleepBackoff(backoffMillis);
            }
        }

        throw lastException == null ? new IOException("Failed to " + operation + ".") : lastException;
    }

    private static void sleepBackoff(long backoffMillis) throws InterruptedException {
        if (backoffMillis > 0) {
            Thread.sleep(backoffMillis);
        }
    }

    private HttpClient createHttpClient(URI downloadUri) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NORMAL);

        ProxyCredentials proxyCredentials = resolveProxyCredentials(downloadUri);
        ServerCredentials serverCredentials = resolveServerCredentials();

        if (proxyCredentials != null) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxyCredentials.host(), proxyCredentials.port())));
        }

        if ((proxyCredentials != null && proxyCredentials.hasAuth()) || serverCredentials != null) {
            builder.authenticator(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    if (proxyCredentials != null
                            && getRequestorType() == RequestorType.PROXY
                            && proxyCredentials.matches(getRequestingHost(), getRequestingPort())) {
                        return proxyCredentials.authentication();
                    }

                    if (serverCredentials != null
                            && getRequestorType() == RequestorType.SERVER
                            && serverCredentials.matches(downloadUri.getHost(), getRequestingHost())) {
                        return serverCredentials.authentication();
                    }

                    return null;
                }
            });
        }

        return builder.build();
    }

    private ProxyCredentials resolveProxyCredentials(URI downloadUri) {
        if (session == null || session.getSettings() == null || session.getSettings().getProxies() == null) {
            return null;
        }

        String scheme = downloadUri.getScheme() == null ? "https" : downloadUri.getScheme();
        for (Proxy proxy : session.getSettings().getProxies()) {
            if (proxy == null || !proxy.isActive()) {
                continue;
            }
            if (proxy.getProtocol() != null
                    && !proxy.getProtocol().isBlank()
                    && !proxy.getProtocol().equalsIgnoreCase(scheme)
                    && !("https".equalsIgnoreCase(scheme) && "http".equalsIgnoreCase(proxy.getProtocol()))) {
                continue;
            }
            if (downloadUri.getHost() != null && matchesNonProxyHosts(downloadUri.getHost(), proxy.getNonProxyHosts())) {
                continue;
            }
            if (proxy.getHost() == null || proxy.getHost().isBlank() || proxy.getPort() <= 0) {
                continue;
            }
            return new ProxyCredentials(proxy);
        }

        return null;
    }

    private ServerCredentials resolveServerCredentials() {
        if (downloadServerId == null || downloadServerId.isBlank()) {
            return null;
        }
        if (session == null || session.getSettings() == null) {
            return null;
        }

        Server server = session.getSettings().getServer(downloadServerId);
        if (server == null || server.getUsername() == null || server.getUsername().isBlank()) {
            return null;
        }

        return new ServerCredentials(server);
    }

    private boolean matchesNonProxyHosts(String host, String nonProxyHosts) {
        if (host == null || nonProxyHosts == null || nonProxyHosts.isBlank()) {
            return false;
        }
        String[] patterns = nonProxyHosts.split("[|,]");
        for (String pattern : patterns) {
            String trimmed = pattern.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String regex = "^" + Pattern.quote(trimmed).replace("\\*", ".*") + "$";
            if (host.toLowerCase().matches(regex.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private void extractTarGz(Path archivePath, Path targetDir, Path expectedBinary) throws IOException, MojoExecutionException {
        try (InputStream fileIn = Files.newInputStream(archivePath);
             InputStream gzipIn = new GzipCompressorInputStream(fileIn);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(gzipIn)) {
            TarArchiveEntry entry;
            boolean extracted = false;
            while ((entry = tarIn.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = Paths.get(entry.getName()).getFileName().toString();
                if (!isLycheeBinaryFileName(entryName)) {
                    continue;
                }
                Path out = targetDir.resolve(entryName);
                try (OutputStream outStream = Files.newOutputStream(out)) {
                    tarIn.transferTo(outStream);
                }
                extracted = true;
            }
            if (!extracted) {
                throw new MojoExecutionException("Downloaded lychee archive did not contain the expected binary.");
            }
        }
        moveFallbackBinaryIfNeeded(targetDir, expectedBinary);
    }

    private void extractZip(Path archivePath, Path targetDir, Path expectedBinary) throws IOException, MojoExecutionException {
        try (InputStream fileIn = Files.newInputStream(archivePath);
             ZipInputStream zipIn = new ZipInputStream(fileIn)) {
            ZipEntry entry;
            boolean extracted = false;
            while ((entry = zipIn.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = Paths.get(entry.getName()).getFileName().toString();
                if (!isLycheeBinaryFileName(entryName)) {
                    continue;
                }
                Path out = targetDir.resolve(entryName);
                Files.copy(zipIn, out, StandardCopyOption.REPLACE_EXISTING);
                extracted = true;
            }
            if (!extracted) {
                throw new MojoExecutionException("Downloaded lychee archive did not contain the expected binary.");
            }
        }
        moveFallbackBinaryIfNeeded(targetDir, expectedBinary);
    }

    private void moveFallbackBinaryIfNeeded(Path targetDir, Path expectedBinary) throws IOException {
        if (Files.exists(expectedBinary)) {
            return;
        }
        Path fallback = targetDir.resolve("lychee");
        if (Files.exists(fallback)) {
            Files.move(fallback, expectedBinary, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void ensureExecutable(Path binary) throws IOException {
        if (LycheePlatform.isWindows(System.getProperty("os.name"))) {
            return;
        }
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(binary, permissions);
        } catch (UnsupportedOperationException ignored) {
            if (!binary.toFile().setExecutable(true)) {
                getLog().warn("Could not mark lychee binary as executable: " + binary);
            }
        }
    }

    private int runLychee(Path binary, List<Path> documents) throws MojoExecutionException {
        List<String> command = new ArrayList<>();
        command.add(binary.toAbsolutePath().toString());
        if (args != null && !args.isEmpty()) {
            command.addAll(args);
        }
        for (Path document : documents) {
            command.add(document.toString());
        }

        getLog().info("Executing lychee: " + String.join(" ", command));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(baseDirectory);
        processBuilder.redirectErrorStream(true);

        try {
            Process process = processBuilder.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    getLog().info("[lychee] " + line);
                }
            }
            return process.waitFor();
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to execute lychee binary", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Execution interrupted", e);
        }
    }

    private String binaryFileName() {
        return LycheePlatform.isWindows(System.getProperty("os.name")) ? "lychee.exe" : "lychee";
    }

    private boolean isLycheeBinaryFileName(String name) {
        return "lychee".equals(name) || binaryFileName().equals(name);
    }

    private static FileSet defaultScanDirectory(Path baseDirectoryPath) {
        FileSet fileSet = new FileSet();
        fileSet.setDirectory(baseDirectoryPath.toString());
        fileSet.setIncludes(new ArrayList<>(DEFAULT_INCLUDES));
        fileSet.setExcludes(new ArrayList<>(DEFAULT_EXCLUDES));
        return fileSet;
    }

    private record ProxyCredentials(String host, int port, String username, String password) {
        ProxyCredentials(Proxy proxy) {
            this(proxy.getHost(), proxy.getPort(), proxy.getUsername(), proxy.getPassword());
        }

        boolean hasAuth() {
            return username != null && !username.isBlank();
        }

        boolean matches(String requestingHost, int requestingPort) {
            return host != null
                    && host.equalsIgnoreCase(requestingHost)
                    && requestingPort == port;
        }

        PasswordAuthentication authentication() {
            String value = password == null ? "" : password;
            return new PasswordAuthentication(username, value.toCharArray());
        }
    }

    private record ServerCredentials(String username, String password) {
        ServerCredentials(Server server) {
            this(server.getUsername(), server.getPassword());
        }

        boolean matches(String configuredHost, String requestingHost) {
            return configuredHost != null
                    && configuredHost.equalsIgnoreCase(requestingHost);
        }

        PasswordAuthentication authentication() {
            String value = password == null ? "" : password;
            return new PasswordAuthentication(username, value.toCharArray());
        }
    }
}
