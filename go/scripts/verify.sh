#!/usr/bin/env bash
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
#
# Run every local gate for the Go client, in order, and stop at the first failure:
# gofmt, go vet (also for GOARCH=386), go mod tidy, staticcheck, go vet and go test of the
# examples, go test -race, go test without -race with a coverage floor on the production
# packages (the allocation tests run only without -race), one run of every benchmark, license
# headers. CI runs this script too.
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
# v0.8.2 needs Go 1.26 or later; under an older go the step is skipped (CI runs it on the
# oldstable and stable legs), so the gate never downloads another toolchain.
STATICCHECK="honnef.co/go/tools/cmd/staticcheck@v0.8.2"
STATICCHECK_MIN_GO=26

# The packages whose statements the coverage floor counts: the production code, not the test
# support packages. Every test package counts toward them, the e2e suite included.
COVER_PACKAGES="./elasticacheintegrations/...,./internal/resp/..."

# The lowest statement coverage of COVER_PACKAGES, in percent, about two points under the
# measured value. The synctest tests need Go 1.25, so Go 1.24 runs fewer and has its own floor.
MIN_COVERAGE=95.0
MIN_COVERAGE_GO124=92.5

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

# GOVERSION is go1.26.3, go1.27rc1 or, for a development build, "devel go1.27-<hash> <date>".
goversion="$(go env GOVERSION)"
go_minor="$(sed -nE 's/^(devel )?go1\.([0-9]+).*/\2/p' <<<"$goversion")"
if [ -z "$go_minor" ]; then
  echo "error: cannot read the Go version from GOVERSION \"$goversion\"." >&2
  exit 1
fi
if [ "$go_minor" -ge "$STATICCHECK_MIN_GO" ]; then
  echo "== staticcheck ./... ($STATICCHECK; go/, go/examples/)"
  go run "$STATICCHECK" ./...
  (cd examples && go run "$STATICCHECK" ./...)
else
  echo "== staticcheck skipped: $goversion is older than go1.$STATICCHECK_MIN_GO"
fi

# The examples use only the public API, so building them checks it from outside the module.
echo "== go vet ./... and go test -race ./... (go/examples/)"
(cd examples && go vet ./... && go test -race -count=1 ./...)

echo "== go test -race -count=1 ${skip[*]+${skip[*]}} ./..."
go test -race -count=1 ${skip[@]+"${skip[@]}"} ./...

case "$(go env GOVERSION)" in
  go1.24 | go1.24.*) min="$MIN_COVERAGE_GO124" ;;
  *) min="$MIN_COVERAGE" ;;
esac

# The allocation tests skip under -race, so the tests also run once without it.
echo "== go test -count=1 ${skip[*]+${skip[*]}} -coverpkg $COVER_PACKAGES ./... (at least $min%)"
go test -count=1 ${skip[@]+"${skip[@]}"} -coverpkg="$COVER_PACKAGES" -coverprofile="$profile" ./...
total="$(go tool cover -func="$profile" | awk '$1 == "total:" { sub(/%$/, "", $NF); print $NF }')"
echo "production statement coverage: $total%"
if ! awk -v total="$total" -v min="$min" 'BEGIN { exit !(total != "" && total + 0 >= min + 0) }'; then
  echo "error: production statement coverage $total% is below $min%." >&2
  exit 1
fi

# One iteration of each benchmark: catches a benchmark that fails, not a slower one.
echo "== go test -run '^\$' -bench . -benchtime 1x ./..."
go test -count=1 -run '^$' -bench . -benchtime 1x ./...

echo "== license headers"
../scripts/check-license-headers.sh
