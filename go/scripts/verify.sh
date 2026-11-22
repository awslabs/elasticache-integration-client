#!/usr/bin/env bash
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
#
# Run every local gate for the Go client, in order, and stop at the first failure:
# gofmt, go vet, staticcheck, go test -race (alignment tests included), go test without -race
# (the allocation tests), license headers.
#
# Usage: go/scripts/verify.sh   (from any directory, also through a symlink)
#
# It uses the go on PATH and the gofmt of that toolchain. To pick a toolchain, run it under
# one, for example
#   mise exec go@1.26.3 -- go/scripts/verify.sh
set -euo pipefail

# staticcheck runs through `go run` at this pinned version, so it is never a module dependency.
# v0.8.2 needs Go 1.26 or later; under an older go it runs on a downloaded newer toolchain.
STATICCHECK="honnef.co/go/tools/cmd/staticcheck@v0.8.2"

# Resolve the script's real location, following symlinks and ignoring CDPATH.
src="${BASH_SOURCE[0]}"
while [ -L "$src" ]; do
  dir="$(CDPATH='' cd -P -- "$(dirname -- "$src")" && pwd)"
  src="$(readlink -- "$src")"
  case "$src" in /*) ;; *) src="$dir/$src" ;; esac
done
CDPATH='' cd -P -- "$(dirname -- "$src")/.."
if [ ! -f go.mod ] || [ ! -f ../scripts/check-license-headers.sh ]; then
  echo "error: $(pwd) is not the go/ directory of the repository." >&2
  exit 1
fi

# A go.work file in or above go/ would change what ./... and the module graph mean.
export GOWORK=off

echo "== gofmt -l ."
unformatted="$("$(go env GOROOT)/bin/gofmt" -l .)"
if [ -n "$unformatted" ]; then
  printf '%s\n' "$unformatted"
  echo "error: the files above are not gofmt-formatted; run gofmt -w on them." >&2
  exit 1
fi

echo "== go vet ./..."
go vet ./...

echo "== staticcheck ./... ($STATICCHECK)"
GOTOOLCHAIN=auto go run "$STATICCHECK" ./...

echo "== go test -race -count=1 ./..."
go test -race -count=1 ./...

# The allocation tests skip under -race, so the tests also run once without it.
echo "== go test -count=1 ./..."
go test -count=1 ./...

echo "== license headers"
../scripts/check-license-headers.sh
