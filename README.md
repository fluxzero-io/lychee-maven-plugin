# lychee-maven-plugin

Maven plugin that downloads a `lychee` binary for the current OS/architecture and runs it against files selected by Maven-style include/exclude patterns.

## Goal

- `lychee:check`
  - Default phase: `verify`

## Defaults

- `lychee.version`: `0.23.0` (latest known at plugin creation time, from `lychee-v0.23.0`, published on 2026-02-13)
- `scanDirectories`: one default entry for `${project.basedir}`
- default includes: markdown/asciidoc/rst/html files
- default excludes: `.git`, `target`, `node_modules`
- `lychee.failOnError`: `true`
- Official GitHub release downloads are SHA-256 verified against GitHub release metadata before execution.

## Usage

```xml
<build>
  <plugins>
    <plugin>
      <groupId>io.fluxzero</groupId>
      <artifactId>lychee-maven-plugin</artifactId>
      <version>0.1.0-SNAPSHOT</version>
      <executions>
        <execution>
          <goals>
            <goal>check</goal>
          </goals>
        </execution>
      </executions>
      <configuration>
        <version>0.23.0</version>

        <scanDirectories>
          <scanDirectory>
            <directory>docs</directory>
            <includes>
              <include>**/*.md</include>
              <include>**/*.adoc</include>
            </includes>
            <excludes>
              <exclude>**/generated/**</exclude>
            </excludes>
          </scanDirectory>
          <scanDirectory>
            <directory>src/site</directory>
            <includes>
              <include>**/*.md</include>
            </includes>
          </scanDirectory>
        </scanDirectories>

        <args>
          <arg>--no-progress</arg>
          <arg>--accept</arg>
          <arg>200..=299</arg>
        </args>
      </configuration>
    </plugin>
  </plugins>
</build>
```

## Configuration Reference

- `skip` (`lychee.skip`, boolean, default `false`)
- `version` (`lychee.version`, string, default `0.23.0`)
- `linuxVariant` (`lychee.linuxVariant`, `gnu|musl`, default `gnu`)
- `assetName` (`lychee.assetName`, string, optional override for exact release asset name)
- `downloadBaseUrl` (`lychee.downloadBaseUrl`, default `https://github.com/lycheeverse/lychee/releases/download`)
- `downloadServerId` (`lychee.downloadServerId`, optional Maven `settings.xml` server id for HTTP auth)
- `verifyChecksum` (`lychee.verifyChecksum`, boolean, default `true`)
- `expectedSha256` (`lychee.expectedSha256`, optional SHA-256 digest override, supports `sha256:` prefix)
- `failOnError` (`lychee.failOnError`, boolean, default `true`)
- `downloadRetries` (int, default `3`, applies to binary + release-metadata HTTP requests)
- `retryBackoffMillis` (long, default `1000`)
- `installDirectory` (Path, default `${project.build.directory}/lychee`)
- `scanDirectories` (List of Maven FileSet-like scan directory blocks)
  - `directory` (Path, default `${project.basedir}` for each block)
  - `includes` (List<String>, Maven-style glob patterns, defaults to built-in doc globs)
  - `excludes` (List<String>, Maven-style glob patterns, defaults to built-in exclude globs)
- `args` (List<String>, extra lychee CLI args)

## Maven Site / Plugin Docs

Run:

```bash
./mvnw site
```

This generates standard Maven Plugin documentation from descriptors in `target/site` (goals and parameters).

## CI and Release

- CI workflow: `.github/workflows/ci.yml` (`verify` + `site`)
- Site publish workflow: `.github/workflows/pages.yml` (publishes Maven site to GitHub Pages)
- Automated versioning/releases: `.github/workflows/release-please.yml` (creates release PRs, tags and GitHub releases)
- Bot PR automerge: `.github/workflows/bot-auto-merge.yml` (enables automerge for `dependabot[bot]` and `release-please[bot]`)
- Publish workflow: `.github/workflows/release.yml` (publishes to Maven Central on `v*` tags)
- Dependabot updates: `.github/dependabot.yml` (Maven + GitHub Actions)
- Maven Central release expects repository secrets:
  - `CENTRAL_USERNAME`
  - `CENTRAL_TOKEN`
  - `GPG_PRIVATE_KEY`
  - `GPG_PASSPHRASE`
- SBOMs are generated during `verify` at `target/bom.xml` and `target/bom.json` and uploaded by CI/release workflows.

## Testing

- Unit tests: `src/test/java` (platform asset resolution).
- End-to-end integration tests: `src/it` using Maven Invoker Plugin.
- Run all tests:

```bash
./mvnw verify
```

The invoker suite covers all plugin configuration parameters with real Maven builds (successful and expected-failure scenarios).

## Notes

- Current upstream assets only provide:
  - macOS: arm64 tarball
  - Windows: x86_64 exe
  - Linux: multiple gnu/musl variants
- If your platform needs a custom asset naming, set `assetName` explicitly.
- SHA-256 verification is enforced for the official GitHub release download URL.
- Release metadata used for checksum resolution is cached at `${installDirectory}/lychee-v<version>/release-metadata.json`.
- If you override `downloadBaseUrl` to a custom mirror/location, checksum verification is skipped with a warning.
- If you use a custom mirror and still want integrity checks, set `expectedSha256`.
