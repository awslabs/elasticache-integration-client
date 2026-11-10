#!/usr/bin/env bash
# Fail when a file in the repository matches a pattern from stdin.
# It checks tracked files and untracked ones that .gitignore doesn't exclude.
#
# Usage: scripts/check-denylist.sh < patterns.txt
# The input holds one case-insensitive Perl regular expression per line. Blank
# lines and lines starting with # are ignored.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

patterns=()
while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in
    '' | '#'*) continue ;;
  esac
  patterns+=(-e "$line")
done

if [ "${#patterns[@]}" -eq 0 ]; then
  echo "error: no patterns on stdin" >&2
  exit 2
fi

# --untracked also checks new files before they are added; ignored files stay
# skipped. git grep exits 0 on a match, 1 on no match, and higher on an error.
# package-lock.json is generated from third-party package metadata.
status=0
# git echoes a malformed pattern in its error text, so its stderr stays out of the log.
git grep -n -I -i -P --untracked "${patterns[@]}" -- . ':!**/package-lock.json' 2>/dev/null || status=$?

case "$status" in
  0)
    echo "error: the lines above match a denylisted pattern. Reword them." >&2
    exit 1
    ;;
  1)
    echo "No denylisted terms found."
    ;;
  *)
    echo "error: git grep failed with exit code $status. Check that every pattern is a valid Perl regular expression." >&2
    exit "$status"
    ;;
esac
