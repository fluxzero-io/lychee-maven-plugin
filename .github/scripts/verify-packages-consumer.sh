#!/usr/bin/env bash
# Resolve and execute the released plugin with an independent project and empty cache.
set -euo pipefail
version=${1:?Usage: verify-packages-consumer.sh RELEASE_VERSION [REPOSITORY_URL]}
repository_url=${2:-https://packages.fluxzero.io/maven}
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || exit 2
root=$(cd "$(dirname "$0")/../.." && pwd)
consumer=$(mktemp -d)
trap 'rm -rf "$consumer"' EXIT
cat > "$consumer/settings.xml" <<'XML'
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"/>
XML
cat > "$consumer/pom.xml" <<XML
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>example.consumer</groupId><artifactId>link-check</artifactId><version>1.0.0</version>
  <pluginRepositories><pluginRepository>
    <id>fluxzero-plugins</id><url>$repository_url</url>
    <snapshots><enabled>false</enabled></snapshots>
  </pluginRepository></pluginRepositories>
  <build><plugins><plugin>
    <groupId>io.fluxzero</groupId><artifactId>lychee-maven-plugin</artifactId><version>$version</version>
    <configuration><args><arg>--offline</arg></args></configuration>
  </plugin></plugins></build>
</project>
XML
printf '# Target\n' > "$consumer/target.md"
printf '[Valid link](target.md)\n' > "$consumer/README.md"
args=(-B -ntp -s "$consumer/settings.xml" -Dmaven.repo.local="$consumer/repository" -f "$consumer/pom.xml" "io.fluxzero:lychee-maven-plugin:$version:check")
"$root/mvnw" "${args[@]}" | tee "$consumer/valid.log"
artifact="$consumer/repository/io/fluxzero/lychee-maven-plugin/$version"
for extension in jar pom; do
  grep -F "lychee-maven-plugin-$version.$extension>fluxzero-plugins=" "$artifact/_remote.repositories"
done
unzip -p "$artifact/lychee-maven-plugin-$version.jar" META-INF/maven/plugin.xml | grep '<goal>check</goal>'
printf '[Broken link](does-not-exist.md)\n' > "$consumer/README.md"
if "$root/mvnw" "${args[@]}" > "$consumer/broken.log" 2>&1; then
  cat "$consumer/broken.log"
  echo 'Broken link unexpectedly passed' >&2
  exit 1
fi
grep 'does-not-exist.md' "$consumer/broken.log"
grep 'lychee reported broken links' "$consumer/broken.log"
echo "Verified $version from Fluxzero Packages: valid link passes, broken link fails."
