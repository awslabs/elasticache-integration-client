#!/usr/bin/env bash
# Fail when a tracked source file has no license header.
#
# Usage: scripts/check-license-headers.sh
# It checks Java, TypeScript, JavaScript, Gradle Kotlin, Python, and Go files
# that git tracks. Files under vendor/, node_modules/, and shared/ (test fixtures)
# are skipped. The header must be in the first 5 lines:
#
#   // Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
#   // SPDX-License-Identifier: Apache-2.0
#
# Python files use # comments. A shebang or Python encoding line may come first.
# In Go files the header goes above any //go:build line.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

missing=()
while IFS= read -r -d '' file; do
  if ! head -n 5 -- "$file" | grep -q 'SPDX-License-Identifier: Apache-2\.0'; then
    missing+=("$file")
  fi
done < <(
  git ls-files -z -- \
    '*.java' '*.kts' '*.ts' '*.mts' '*.cts' '*.js' '*.mjs' '*.cjs' '*.py' '*.go' \
    ':!:**/vendor/**' ':!:vendor/**' \
    ':!:**/node_modules/**' ':!:node_modules/**' \
    ':!:shared/**'
)

if [ "${#missing[@]}" -gt 0 ]; then
  printf '%s\n' "${missing[@]}"
  echo "error: the files above have no license header in their first 5 lines." >&2
  echo "Add the header shown at the top of scripts/check-license-headers.sh." >&2
  exit 1
fi

echo "All source files have a license header."
