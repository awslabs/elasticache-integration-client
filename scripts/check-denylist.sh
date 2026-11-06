#!/usr/bin/env bash
# Fail when a file in the repository contains a term from scripts/denylist.txt.
# It checks tracked files and untracked ones that .gitignore doesn't exclude.
#
# Usage: scripts/check-denylist.sh
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
list=scripts/denylist.txt

patterns=()
while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in
    '' | '#'*) continue ;;
  esac
  patterns+=(-e "$line")
done < "$list"

if [ "${#patterns[@]}" -eq 0 ]; then
  echo "error: $list has no patterns" >&2
  exit 2
fi

# --untracked also checks new files before they are added; ignored files stay
# skipped. git grep exits 0 on a match, 1 on no match, and higher on an error.
# package-lock.json is generated from third-party package metadata.
status=0
git grep -n -I -i -P --untracked "${patterns[@]}" -- . ":!$list" ':!**/package-lock.json' || status=$?

case "$status" in
  0)
    echo "error: the lines above use a term listed in $list. Reword them." >&2
    exit 1
    ;;
  1)
    echo "No denylisted terms found."
    ;;
  *)
    echo "error: git grep failed with exit code $status" >&2
    exit "$status"
    ;;
esac
