#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "usage: $0 <artifact-directory> <version>" >&2
    exit 2
fi

artifact_dir=$1
version=$2
artifact_prefix="photon-java-$version"
listing_dir=$(mktemp -d)
trap 'rm -rf "$listing_dir"' EXIT

for artifact in \
    "$artifact_prefix.jar" \
    "$artifact_prefix-sources.jar" \
    "$artifact_prefix-javadoc.jar" \
    "$artifact_prefix.pom"; do
    if [[ ! -s "$artifact_dir/$artifact" ]]; then
        echo "missing or empty release artifact: $artifact" >&2
        exit 1
    fi
done

jar tf "$artifact_dir/$artifact_prefix.jar" > "$listing_dir/main"
jar tf "$artifact_dir/$artifact_prefix-sources.jar" > "$listing_dir/sources"
jar tf "$artifact_dir/$artifact_prefix-javadoc.jar" > "$listing_dir/javadoc"

grep -q '^software/amazon/photon/session/PhotonClient.class$' "$listing_dir/main"
grep -q \
    '^software/amazon/elasticache/auth/ElastiCacheIamAuthTokenProvider.class$' \
    "$listing_dir/main"
grep -q '^META-INF/LICENSE$' "$listing_dir/main"
grep -q '^META-INF/NOTICE$' "$listing_dir/main"
grep -q '^software/amazon/photon/session/PhotonClient.java$' "$listing_dir/sources"
grep -q '^software/amazon/photon/session/PhotonClient.html$' "$listing_dir/javadoc"

pom="$artifact_dir/$artifact_prefix.pom"
grep -q '<groupId>software.amazon.photon</groupId>' "$pom"
grep -q '<artifactId>photon-java</artifactId>' "$pom"
grep -q "<version>$version</version>" "$pom"
grep -q '<name>Photon Java Client</name>' "$pom"
grep -q '<license>' "$pom"
grep -q '<scm>' "$pom"

if grep -q '^software/amazon/photon/.*Test.class$' "$listing_dir/main"; then
    echo "main JAR contains test classes" >&2
    exit 1
fi
