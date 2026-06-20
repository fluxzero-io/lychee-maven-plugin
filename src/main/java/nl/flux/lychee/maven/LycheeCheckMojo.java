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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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

    static final String DEFAULT_LYCHEE_VERSION = "0.24.2";
    static final String OFFICIAL_RELEASE_BASE_URL = "https://github.com/lycheeverse/lychee/releases/download";
    static final int CONNECT_TIMEOUT_SECONDS = 30;
    static final int READ_TIMEOUT_SECONDS = 120;
    static final Pattern SHA256_PATTERN = Pattern.compile("(?:sha256:)?([0-9a-fA-F]{64})");
    static final Pattern LYCHEE_SOURCE_HEADER_PATTERN = Pattern.compile("^\\[(.+)]\\:$");
    static final Pattern LYCHEE_LOCATION_PATTERN = Pattern.compile("\\(at\\s+(\\d+)(?::(\\d+))?\\)");
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
            verifyDownloadedAssetChecksumIfSupported(downloadUri, resolvedAsset, downloaded);

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

    private void verifyDownloadedAssetChecksumIfSupported(URI downloadUri, String resolvedAsset, Path downloadedAsset)
            throws IOException, InterruptedException, MojoExecutionException {
        if (!verifyChecksum) {
            getLog().warn("Skipping SHA-256 verification because lychee.verifyChecksum=false.");
            return;
        }

        String expectedSha256 = resolveExpectedSha256(downloadUri);
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

    private String resolveExpectedSha256(URI downloadUri)
            throws IOException, InterruptedException, MojoExecutionException {
        String normalizedConfigured = normalizeSha256(expectedSha256);
        if (normalizedConfigured != null) {
            return normalizedConfigured;
        }

        if (!isOfficialGithubReleaseDownload(downloadUri)) {
            getLog().warn("Skipping SHA-256 verification for non-official download URL: " + downloadUri);
            return null;
        }

        URI checksumUri = URI.create(downloadUri + ".sha256");
        //noinspection resource
        HttpClient client = createHttpClient(checksumUri);
        HttpRequest checksumRequest = HttpRequest.newBuilder(checksumUri)
                .timeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
                .header("Accept", "text/plain, application/octet-stream;q=0.9, */*;q=0.8")
                .GET()
                .build();
        HttpResponse<String> checksumResponse = sendWithRetry(
                client,
                checksumRequest,
                HttpResponse.BodyHandlers.ofString(),
                "checksum sidecar download",
                statusCode -> statusCode >= 500);
        if (checksumResponse.statusCode() >= 400) {
            getLog().warn("No SHA-256 sidecar available for official download. HTTP "
                    + checksumResponse.statusCode() + " from " + checksumUri);
            return null;
        }

        String digest = findSha256DigestInText(checksumResponse.body());
        if (digest == null) {
            throw new MojoExecutionException("SHA-256 sidecar did not contain a valid digest: " + checksumUri);
        }
        return digest;
    }

    static boolean isOfficialGithubReleaseDownload(URI uri) {
        if (uri == null) {
            return false;
        }
        String normalized = uri.toString();
        return normalized.startsWith(OFFICIAL_RELEASE_BASE_URL + "/");
    }

    static String findSha256DigestInText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        java.util.regex.Matcher matcher = SHA256_PATTERN.matcher(value);
        return matcher.find() ? matcher.group(1).toLowerCase() : null;
    }

    static String normalizeSha256(String value) throws MojoExecutionException {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.regionMatches(true, 0, "sha256:", 0, "sha256:".length())) {
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
        Path inputList = null;
        try {
            inputList = writeLycheeInputList(documents);

            List<String> command = new ArrayList<>();
            command.add(binary.toAbsolutePath().toString());
            command.addAll(normalizeArgsForClickableOutput(args));
            command.add("--files-from");
            command.add(inputList.toString());

            getLog().info("Executing lychee: " + String.join(" ", command));

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(baseDirectory);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() > 0) {
                        output.append('\n');
                    }
                    output.append(line);
                }
            }
            int exitCode = process.waitFor();
            for (LycheeOutputLine line : formatLycheeOutputForConsole(output.toString(), baseDirectory.toPath())) {
                if (line.message().isBlank()) {
                    continue;
                }
                if (line.issue()) {
                    getLog().warn(line.message());
                } else {
                    getLog().info("[lychee] " + line.message());
                }
            }
            return exitCode;
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to execute lychee binary", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Execution interrupted", e);
        } finally {
            if (inputList != null) {
                try {
                    Files.deleteIfExists(inputList);
                } catch (IOException e) {
                    getLog().debug("Could not delete temporary lychee input list: " + inputList, e);
                }
            }
        }
    }

    private Path writeLycheeInputList(List<Path> documents) throws IOException {
        Path inputDirectory = installDirectory.toPath();
        Files.createDirectories(inputDirectory);
        Path inputList = Files.createTempFile(inputDirectory, "lychee-inputs-", ".txt");
        Files.write(inputList, documents.stream().map(Path::toString).toList(), StandardCharsets.UTF_8);
        return inputList;
    }

    static List<String> normalizeArgsForClickableOutput(List<String> originalArgs) {
        List<String> normalized = new ArrayList<>();
        boolean noProgressConfigured = false;
        if (originalArgs != null) {
            for (int i = 0; i < originalArgs.size(); i++) {
                String arg = originalArgs.get(i);
                if (arg == null || arg.isBlank()) {
                    continue;
                }

                if ("--format".equals(arg) || "-f".equals(arg)) {
                    i++;
                    continue;
                }

                if (arg.startsWith("--format=") || arg.startsWith("-f=")) {
                    continue;
                }

                if ("--mode".equals(arg)) {
                    i++;
                    continue;
                }

                if (arg.startsWith("--mode=")) {
                    continue;
                }

                if ("--no-progress".equals(arg) || "-n".equals(arg) || arg.startsWith("--no-progress=")) {
                    noProgressConfigured = true;
                }

                normalized.add(arg);
            }
        }

        normalized.add("--format");
        normalized.add("compact");
        normalized.add("--mode");
        normalized.add("plain");
        if (!noProgressConfigured) {
            normalized.add("--no-progress");
        }
        return normalized;
    }

    static List<LycheeOutputLine> formatLycheeOutputForConsole(String lycheeOutput, Path baseDirectoryPath) {
        if (lycheeOutput == null || lycheeOutput.isBlank()) {
            return List.of();
        }

        List<LycheeOutputLine> formatted = new ArrayList<>();
        Path currentSource = null;
        for (String line : lycheeOutput.split("\\R")) {
            Matcher headerMatcher = LYCHEE_SOURCE_HEADER_PATTERN.matcher(line);
            if (headerMatcher.matches()) {
                currentSource = resolveSourcePath(baseDirectoryPath, headerMatcher.group(1));
                formatted.add(new LycheeOutputLine(line, false));
                continue;
            }

            Matcher locationMatcher = LYCHEE_LOCATION_PATTERN.matcher(line);
            if (currentSource != null && locationMatcher.find()) {
                String location = currentSource + ":" + locationMatcher.group(1);
                if (locationMatcher.group(2) != null) {
                    location += ":" + locationMatcher.group(2);
                }
                formatted.add(new LycheeOutputLine(location + ": " + line, true));
            } else {
                formatted.add(new LycheeOutputLine(line, false));
            }
        }
        return formatted;
    }

    private static Path resolveSourcePath(Path baseDirectoryPath, String sourceFile) {
        try {
            Path candidate = Paths.get(sourceFile);
            if (!candidate.isAbsolute()) {
                candidate = baseDirectoryPath.resolve(candidate);
            }
            return candidate.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
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

    record LycheeOutputLine(String message, boolean issue) {
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
