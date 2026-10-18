#!/usr/bin/env bash
# Packs Amazon.Photon, checks the package contents, installs it into a throwaway console app and
# runs that app against the test suite's mock cache (PackSmokeTests). No AWS access.
# Usage: scripts/pack-smoke.sh [--no-build]   (--no-build reuses an existing Release build)
set -euo pipefail
cd "$(dirname "$0")/.."

fail() {
  echo "pack-smoke: $*" >&2
  exit 1
}

[[ $# -eq 0 || ($# -eq 1 && "$1" == "--no-build") ]] || fail "usage: $0 [--no-build]"
no_build="${1:-}"

artifacts="$PWD/artifacts/pack-smoke"
rm -rf "$artifacts"
dotnet pack src/Amazon.Photon -c Release $no_build -o "$artifacts"
nupkg="$artifacts/Amazon.Photon.0.0.1.nupkg"
snupkg="$artifacts/Amazon.Photon.0.0.1.snupkg"
[[ -f "$nupkg" && -f "$snupkg" ]] || fail "expected $nupkg and $snupkg"

listing=$(unzip -Z1 "$nupkg")
for entry in Amazon.Photon.nuspec lib/net8.0/Amazon.Photon.dll lib/net8.0/Amazon.Photon.xml README.md LICENSE; do
  grep -qxF "$entry" <<<"$listing" || fail "the package is missing $entry"
done
unexpected=$(grep -vE '^(Amazon\.Photon\.nuspec|lib/net8\.0/Amazon\.Photon\.(dll|xml)|README\.md|LICENSE|_rels/\.rels|\[Content_Types\]\.xml|package/services/metadata/core-properties/[0-9a-f]+\.psmdcp)$' <<<"$listing" || true)
[[ -z "$unexpected" ]] || fail "the package has unexpected entries:"$'\n'"$unexpected"

nuspec=$(unzip -p "$nupkg" Amazon.Photon.nuspec)
for field in '<id>Amazon.Photon</id>' '<version>0.0.1</version>' '<license type="expression">Apache-2.0</license>' \
  '<readme>README.md</readme>' '<repository type="git" url="https://github.com/awslabs/photon" commit="'; do
  grep -qF "$field" <<<"$nuspec" || fail "the nuspec is missing $field"
done

unzip -Z1 "$snupkg" | grep -qxF lib/net8.0/Amazon.Photon.pdb || fail "the symbol package has no pdb"
# The SDK's built-in Source Link maps sources only for a GitHub remote.
if git remote get-url origin 2>/dev/null | grep -q 'github\.com'; then
  # Extracted first: grep -q on a pipe would SIGPIPE unzip, which pipefail reports as a failure.
  unzip -p "$snupkg" lib/net8.0/Amazon.Photon.pdb >"$artifacts/Amazon.Photon.pdb"
  grep -aq 'raw.githubusercontent.com' "$artifacts/Amazon.Photon.pdb" || fail "the pdb has no Source Link"
else
  echo "pack-smoke: Source Link check skipped, origin is not GitHub"
fi

app=$(mktemp -d)
trap 'rm -rf "$app"' EXIT
cp scripts/pack-smoke/PackSmoke.csproj scripts/pack-smoke/Program.cs global.json "$app/"
cat >"$app/nuget.config" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<configuration>
  <packageSources>
    <clear />
    <add key="local" value="$artifacts" />
    <add key="nuget.org" value="https://api.nuget.org/v3/index.json" />
  </packageSources>
</configuration>
EOF
# A private packages folder, so a stale Amazon.Photon 0.0.1 in the user cache is never used.
dotnet build "$app/PackSmoke.csproj" -c Release --packages "$app/packages" -o "$app/out"

PHOTON_PACK_SMOKE_APP="$app/out/PackSmoke.dll" \
  dotnet test test/Amazon.Photon.Tests -c Release $no_build --filter "FullyQualifiedName~PackSmokeTests" \
  -- RunConfiguration.TreatNoTestsAsError=true xUnit.FailSkips=true
echo "pack-smoke: passed for $(basename "$nupkg")"
