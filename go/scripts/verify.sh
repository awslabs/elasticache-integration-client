#!/usr/bin/env bash
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
#
# Run every local gate for the Go client, in order, and stop at the first failure:
# gofmt, go vet (also for GOARCH=386), go mod tidy, staticcheck, go vet and go test of the
# examples, go test -race, go test without -race with a coverage floor (the allocation tests
# run only without -race), license headers. CI runs this script too.
#
# Usage: go/scripts/verify.sh [--skip-alignment] [--coverprofile FILE]
#   --skip-alignment     skip the TestAlignment* tests; CI runs them in alignment-ci.yml
#   --coverprofile FILE  keep the coverage profile in FILE
# It runs from any directory, also through a symlink.
#
# It uses the go on PATH and the gofmt of that toolchain. To pick a toolchain, run it under
# one, for example
#   mise exec go@1.26.3 -- go/scripts/verify.sh
set -euo pipefail

# staticcheck runs through `go run` at this pinned version, so it is never a module dependency.
# v0.8.2 needs Go 1.26 or later; under an older go it runs on a downloaded newer toolchain.
STATICCHECK="honnef.co/go/tools/cmd/staticcheck@v0.8.2"

# The lowest statement coverage the go test pass may report, in percent.
MIN_COVERAGE=90.0

skip=()
profile=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --skip-alignment) skip=(-skip '^TestAlignment') ;;
    --coverprofile)
      [ "$#" -ge 2 ] || { echo "error: --coverprofile needs a file" >&2; exit 2; }
      profile="$2"
      shift
      ;;
    *)
      echo "usage: $0 [--skip-alignment] [--coverprofile FILE]" >&2
      exit 2
      ;;
  esac
  shift
done

# Resolve the script's real location, following symlinks and ignoring CDPATH.
src="${BASH_SOURCE[0]}"
while [ -L "$src" ]; do
  dir="$(CDPATH='' cd -P -- "$(dirname -- "$src")" && pwd)"
  src="$(readlink -- "$src")"
  case "$src" in /*) ;; *) src="$dir/$src" ;; esac
done
# Resolve a relative profile path before leaving the caller's directory.
if [ -n "$profile" ]; then
  case "$profile" in /*) ;; *) profile="$(pwd)/$profile" ;; esac
fi
CDPATH='' cd -P -- "$(dirname -- "$src")/.."
if [ ! -f go.mod ] || [ ! -f examples/go.mod ] || [ ! -f ../scripts/check-license-headers.sh ]; then
  echo "error: $(pwd) is not the go/ directory of the repository." >&2
  exit 1
fi

# A go.work file in or above go/ would change what ./... and the module graph mean.
export GOWORK=off

if [ -z "$profile" ]; then
  profile="$(mktemp)"
  trap 'rm -f "$profile"' EXIT
fi

echo "== gofmt -l ."
unformatted="$("$(go env GOROOT)/bin/gofmt" -l .)"
if [ -n "$unformatted" ]; then
  printf '%s\n' "$unformatted"
  echo "error: the files above are not gofmt-formatted; run gofmt -w on them." >&2
  exit 1
fi

echo "== go vet ./..."
go vet ./...

# The codec's length arithmetic must stay correct where int is 32 bits.
echo "== GOARCH=386 go vet ./..."
GOARCH=386 go vet ./...

echo "== go mod tidy -diff (go/, go/examples/)"
go mod tidy -diff
(cd examples && go mod tidy -diff)

echo "== staticcheck ./... ($STATICCHECK; go/, go/examples/)"
GOTOOLCHAIN=auto go run "$STATICCHECK" ./...
(cd examples && GOTOOLCHAIN=auto go run "$STATICCHECK" ./...)

# The examples use only the public API, so building them checks it from outside the module.
echo "== go vet ./... and go test -race ./... (go/examples/)"
(cd examples && go vet ./... && go test -race -count=1 ./...)

echo "== go test -race -count=1 ${skip[*]+${skip[*]}} ./..."
go test -race -count=1 ${skip[@]+"${skip[@]}"} ./...

# The allocation tests skip under -race, so the tests also run once without it.
echo "== go test -count=1 ${skip[*]+${skip[*]}} -coverprofile ./... (at least $MIN_COVERAGE%)"
go test -count=1 ${skip[@]+"${skip[@]}"} -coverprofile="$profile" ./...
total="$(go tool cover -func="$profile" | awk '$1 == "total:" { sub(/%$/, "", $NF); print $NF }')"
echo "total statement coverage: $total%"
if ! awk -v total="$total" -v min="$MIN_COVERAGE" 'BEGIN { exit !(total != "" && total + 0 >= min + 0) }'; then
  echo "error: statement coverage $total% is below $MIN_COVERAGE%." >&2
  exit 1
fi

echo "== license headers"
../scripts/check-license-headers.sh
