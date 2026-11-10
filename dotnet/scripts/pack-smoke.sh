#!/usr/bin/env bash
# Packs Amazon.ElastiCache.Integrations, checks the package contents, installs it into a throwaway console app and
# runs that app against the test suite's mock cache (PackSmokeTests). No AWS access.
# Usage: scripts/pack-smoke.sh [--no-build | --published]
#   --no-build   reuses an existing Release build
#   --published  skips the pack and installs the csproj's version from nuget.org
set -euo pipefail
cd "$(dirname "$0")/.."

fail() {
  echo "pack-smoke: $*" >&2
  exit 1
}

[[ $# -eq 0 || ($# -eq 1 && ("$1" == "--no-build" || "$1" == "--published")) ]] || fail "usage: $0 [--no-build | --published]"
mode="${1:-}"
no_build=()
if [[ "$mode" == "--no-build" ]]; then
  no_build=(--no-build)
fi

version=$(dotnet msbuild src/Amazon.ElastiCache.Integrations -getProperty:Version)
[[ -n "$version" ]] || fail "the csproj has no Version"
artifacts="$PWD/artifacts/pack-smoke"
nupkg="$artifacts/Amazon.ElastiCache.Integrations.$version.nupkg"
snupkg="$artifacts/Amazon.ElastiCache.Integrations.$version.snupkg"

pack_and_check() {
  rm -rf "$artifacts"
  dotnet pack src/Amazon.ElastiCache.Integrations -c Release ${no_build[@]+"${no_build[@]}"} -o "$artifacts"
  [[ -f "$nupkg" && -f "$snupkg" ]] || fail "expected $nupkg and $snupkg"

  local listing unexpected nuspec field entry
  listing=$(unzip -Z1 "$nupkg")
  for entry in Amazon.ElastiCache.Integrations.nuspec lib/net8.0/Amazon.ElastiCache.Integrations.dll lib/net8.0/Amazon.ElastiCache.Integrations.xml README.md LICENSE; do
    grep -qxF "$entry" <<<"$listing" || fail "the package is missing $entry"
  done
  unexpected=$(grep -vE '^(Amazon\.ElastiCache\.Integrations\.nuspec|lib/net8\.0/Amazon\.ElastiCache\.Integrations\.(dll|xml)|README\.md|LICENSE|_rels/\.rels|\[Content_Types\]\.xml|package/services/metadata/core-properties/[0-9a-f]+\.psmdcp)$' <<<"$listing" || true)
  [[ -z "$unexpected" ]] || fail "the package has unexpected entries:"$'\n'"$unexpected"

  nuspec=$(unzip -p "$nupkg" Amazon.ElastiCache.Integrations.nuspec)
  for field in '<id>Amazon.ElastiCache.Integrations</id>' "<version>$version</version>" '<license type="expression">Apache-2.0</license>' \
    '<readme>README.md</readme>' '<repository type="git" url="https://github.com/awslabs/photon" commit="'; do
    grep -qF "$field" <<<"$nuspec" || fail "the nuspec is missing $field"
  done

  unzip -Z1 "$snupkg" | grep -qxF lib/net8.0/Amazon.ElastiCache.Integrations.pdb || fail "the symbol package has no pdb"
  # The SDK's built-in Source Link maps sources only for a GitHub remote.
  if git remote get-url origin 2>/dev/null | grep -q 'github\.com'; then
    # Extracted first: grep -q on a pipe would SIGPIPE unzip, which pipefail reports as a failure.
    unzip -p "$snupkg" lib/net8.0/Amazon.ElastiCache.Integrations.pdb >"$artifacts/Amazon.ElastiCache.Integrations.pdb"
    grep -aq 'raw.githubusercontent.com' "$artifacts/Amazon.ElastiCache.Integrations.pdb" || fail "the pdb has no Source Link"
  else
    echo "pack-smoke: Source Link check skipped, origin is not GitHub"
  fi
}

local_source=""
if [[ "$mode" == "--published" ]]; then
  package="Amazon.ElastiCache.Integrations $version from nuget.org"
else
  pack_and_check
  package=$(basename "$nupkg")
  local_source="<add key=\"local\" value=\"$artifacts\" />"
fi

app=$(mktemp -d)
trap 'rm -rf "$app"' EXIT
cp scripts/pack-smoke/PackSmoke.csproj scripts/pack-smoke/Program.cs global.json "$app/"
cat >"$app/nuget.config" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<configuration>
  <packageSources>
    <clear />
    $local_source
    <add key="nuget.org" value="https://api.nuget.org/v3/index.json" />
  </packageSources>
</configuration>
EOF
# A private packages folder, so a stale copy of this version in the user cache is never used.
dotnet build "$app/PackSmoke.csproj" -c Release -p:ElastiCacheIntegrationsVersion="$version" --packages "$app/packages" -o "$app/out"

ELASTICACHE_INTEGRATIONS_PACK_SMOKE_APP="$app/out/PackSmoke.dll" \
  dotnet test test/Amazon.ElastiCache.Integrations.Tests -c Release ${no_build[@]+"${no_build[@]}"} --filter "FullyQualifiedName~PackSmokeTests" \
  -- RunConfiguration.TreatNoTestsAsError=true xUnit.FailSkips=true
echo "pack-smoke: passed for $package"
