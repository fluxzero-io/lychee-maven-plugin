# lychee-maven-plugin

Maven plugin that downloads a `lychee` binary for the current OS/architecture and runs it against files selected by Maven-style include/exclude patterns.

## Goal

- `lychee:check`
  - Default phase: `verify`

## Defaults

- `lychee.version`: `0.24.2`
- `scanDirectories`: one default entry for `${project.basedir}`
- default includes: markdown/asciidoc/rst/html files
- default excludes: `.git`, `target`, `node_modules`
- `lychee.failOnError`: `true`
- lychee runs with compact/plain console output; issue lines are rewritten as absolute `path:line:column` messages for IDE console navigation.
- Official GitHub release downloads are SHA-256 verified against the release `.sha256` sidecar before execution.

## Usage

```xml
<build>
  <plugins>
    <plugin>
      <groupId>io.fluxzero</groupId>
      <artifactId>lychee-maven-plugin</artifactId>
      <version>0.1.4</version>
      <executions>
        <execution>
          <goals>
            <goal>check</goal>
          </goals>
        </execution>
      </executions>
      <configuration>
        <version>0.24.2</version>

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
- `version` (`lychee.version`, string, default `0.24.2`)
- `linuxVariant` (`lychee.linuxVariant`, `gnu|musl`, default `gnu`)
- `assetName` (`lychee.assetName`, string, optional override for exact release asset name)
- `downloadBaseUrl` (`lychee.downloadBaseUrl`, default `https://github.com/lycheeverse/lychee/releases/download`)
- `downloadServerId` (`lychee.downloadServerId`, optional Maven `settings.xml` server id for HTTP auth)
- `verifyChecksum` (`lychee.verifyChecksum`, boolean, default `true`)
- `expectedSha256` (`lychee.expectedSha256`, optional SHA-256 digest override, supports `sha256:` prefix)
- `failOnError` (`lychee.failOnError`, boolean, default `true`)
- `downloadRetries` (int, default `3`, applies to binary + checksum sidecar HTTP requests)
- `retryBackoffMillis` (long, default `1000`)
- `installDirectory` (Path, default `${project.build.directory}/lychee`)
- `scanDirectories` (List of Maven FileSet-like scan directory blocks)
  - `directory` (Path, default `${project.basedir}` for each block)
  - `includes` (List<String>, Maven-style glob patterns, defaults to built-in doc globs)
  - `excludes` (List<String>, Maven-style glob patterns, defaults to built-in exclude globs)
- `args` (List<String>, extra lychee CLI args; `--format` and `--mode` are normalized by the plugin for clickable console output)

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

- Unit tests: `src/test/java` (platform asset resolution, checksum parsing, and output location hints).
- End-to-end integration tests: `src/it` using Maven Invoker Plugin.
- Run all tests:

```bash
./mvnw verify
```

The invoker suite covers all plugin configuration parameters with real Maven builds (successful and expected-failure scenarios).

## Notes

- Current upstream assets provide:
  - macOS: arm64 and x86_64 tarballs
  - Windows: x86_64 zip
  - Linux: multiple gnu/musl variants
- If your platform needs a custom asset naming, set `assetName` explicitly.
- SHA-256 verification is enabled for the official GitHub release download URL.
- SHA-256 verification uses the `.sha256` sidecar published next to the official release asset.
- If no sidecar is available for an older official release, verification is skipped with a warning unless `expectedSha256` is set.
- If you override `downloadBaseUrl` to a custom mirror/location, checksum verification is skipped with a warning.
- If you use a custom mirror and still want integrity checks, set `expectedSha256`.
