# Developing the ElastiCache Integrations Go client

## Building and testing

The module is `go/` (`go.mod`, `go 1.24.0`). The `go` directive is the floor users need, not the toolchain you develop with: use Go 1.24 or later, and Go 1.26 or later for staticcheck (the gate fetches a newer toolchain for it when needed). Do not add a `toolchain` line.

- `scripts/verify.sh` is the complete local gate. It runs, in order and stopping at the first failure: `gofmt -l .`, `go vet ./...`, `GOARCH=386 go vet ./...` (the RESP codec's length arithmetic must hold where `int` is 32 bits), `go mod tidy -diff` for `go/` and `go/examples/`, staticcheck (pinned in the script, run with `go run`, never a module dependency) for both modules, `go vet` and `go test -race` of the examples, `go test -race -count=1 ./...`, `go test -count=1 ./...` with a coverage floor of 90% of statements (the allocation tests skip under `-race`, so they run only here), and the repository's license header check.
  - `--skip-alignment` leaves out the alignment tests, as CI's `go-ci.yml` does.
  - `--coverprofile FILE` keeps the coverage profile.
  - It uses the `go` on `PATH`; run it under another toolchain with, for example, `mise exec go@1.24 -- scripts/verify.sh`.
- `gofmt -w .` fixes formatting.
- `go test -run 'TestName' ./elasticacheintegrations` runs focused tests. Add `-race` for anything with goroutines.
- Tests with `testing/synctest` (files named `*_synctest_test.go`, `//go:build go1.25`) control time and check for leaked goroutines. They build only on Go 1.25 or later, so the Go 1.24 run skips them.

Two further suites run in CI and can be reproduced locally:

- The cross-client alignment tests, which run the fixtures in `shared/` that every client must agree on: `go test -run '^TestAlignment' ./...`. The client CI job skips them (`-skip '^TestAlignment'`) and `alignment-ci.yml` runs them. A fixture field the Go tests do not read fails them, because the fixtures are decoded with unknown fields refused.
- The weekly compatibility job (`compat.yml`, `go-latest`), which moves every dependency to its newest release with `go get -u -t ./...` and `go mod tidy`, then runs the gate. The `go.mod` requirements are minimum versions, and Go builds with exactly those, so every normal run tests the oldest allowed AWS SDK.

CI also runs `govulncheck` on both modules with the newest Go release:

```sh
go run golang.org/x/vuln/cmd/govulncheck@v1.10.0 ./...
(cd examples && go run golang.org/x/vuln/cmd/govulncheck@v1.10.0 ./...)
```

On an older local toolchain it also reports vulnerabilities fixed in later Go patch releases of the standard library.

### Fuzzing and benchmarks

The parsers have fuzz targets: `FuzzDecode` and `FuzzReader` in `internal/resp`, and `FuzzParseRequestReply`, `FuzzParsePush`, `FuzzParseConfigReply`, `FuzzParseEndpoint`, `FuzzCopyOverrides` and others in `elasticacheintegrations`. `go test` runs their seeds. Fuzz one for a while after changing its parser:

```sh
go test -run '^$' -fuzz '^FuzzDecode$' -fuzztime 5m ./internal/resp
```

Benchmarks report allocations and do not gate CI. Compare before and after a change on the same host:

```sh
go test -run '^$' -bench 'GetItemHit|GetItemPlain' -benchmem ./elasticacheintegrations
```

`BenchmarkGetItemHit` drives a hit through a real `dynamodb.Client` at 1 KiB, 64 KiB and 390 KiB, next to `BenchmarkGetItemPlain`, the same call without the cache.

## Live DynamoDB E2E example

`go/examples` is its own module, so its dependencies (such as `aws-sdk-go-v2/config`) never become the client's. Copy the repository-root [`.env.example`](../.env.example) to `.env` and fill it in, then run from `go/examples/`:

```sh
go run ./local-e2e
```

It loads the repository-root `.env` (variables already set in the environment win) and runs the same scenarios as the Java, Node and Python examples against the cache and table: a hit after an origin delete, concurrent reads, Query, writes through the cache, an origin error, a large binary item, batch reads and Scan, and deletes. Each run uses a fresh item id so the first read is a cache miss; set `ELASTICACHE_ITEM_ID` only to pin one. A scenario that reads seeded items through the cache waits 1 s after seeding, because a miss reads DynamoDB with an eventually consistent read. It removes every item it seeds, also after a failure. Supported settings: `ELASTICACHE_CACHE_HOST`, `ELASTICACHE_CACHE_PORT`, `ELASTICACHE_CACHE_NAME`, `ELASTICACHE_CACHE_USER_ID`, `ELASTICACHE_CA_CERT`, `ELASTICACHE_REQUEST_TIMEOUT_SECONDS`, `ELASTICACHE_CONCURRENCY`, `ELASTICACHE_TABLE_NAME`, `AWS_REGION` (default `us-east-1`) and the AWS credentials of the standard chain.

The Go client dials the cache host itself and checks its certificate against that name, so the example cannot redirect it to an SSH tunnel the way the Java and Node examples do. To use a tunnel, add `<tunnel address> <cache host>` to `/etc/hosts` and set `ELASTICACHE_TUNNEL_HOST` to the tunnel address; the example checks the mapping before it starts.

## Live validation example

`go run ./live-validation ops` (from `go/examples/`) checks the client against a real cache with the DynamoDB integration and a table with a string partition key `id`. It runs the ten routed operations and compares each result with a consistent read by a plain DynamoDB client, proves a hit with a stale read, checks that every write through the cache invalidates the cached item, checks that `DescribeTable` goes to DynamoDB, and checks the resolved config from the client's log. It counts the calls the client sends to DynamoDB itself through the `HTTPClient` it wraps. Settings come from the environment, with the names `local-e2e` uses: `ELASTICACHE_CACHE_HOST`, `ELASTICACHE_CACHE_PORT`, `ELASTICACHE_CACHE_NAME`, `ELASTICACHE_CACHE_USER_ID`, `ELASTICACHE_TABLE_NAME`, `AWS_REGION` and the AWS credentials of the standard chain. It prints one line per check, exits 0 only when every check passes, and deletes its `go-live-<run>-*` items at the end, also after a failure or a signal.

Three more modes check what the mock cache cannot:

- `auth` breaks the client's credentials right after start-up (a wrong secret key, so the cache must refuse the token) and waits up to 19 minutes for the first periodic re-AUTH (900 s ±20%). It checks that the cache refuses it with `WRONGPASS`, that misses are still routed through the connection, and that re-AUTH succeeds once the credentials are good again.
- `push` proves async replies: on one connection, the reply to a hit started 1 ms after a miss on a 200 KiB item arrives more than 1 ms before the miss's, in at least 10 of 20 trials. Reply times are taken before the SDK decodes the body, because decoding the 200 KiB item alone takes about a millisecond.
- `fallback` puts a TCP relay on `127.0.0.1` in front of one cache address, given as `LIVE_RELAY_TARGET` (`<ip>:<port>`, not a loopback address). Map the cache host to `127.0.0.1` in `/etc/hosts` for the run; the mode refuses to start otherwise. It checks that start-up against a refusing cache fails within `ConnectionTimeout`, that a read during an outage goes to DynamoDB and a strict client's read fails with `*CacheUnavailableError`, that both clients reconnect, and that a read on a hung cache times out and goes to DynamoDB.

## Releasing

Go modules are published by git tag; there is no artifact to build or upload. The module lives in `go/`, so its release tags are `go/vMAJOR.MINOR.PATCH` (or `go/vMAJOR.MINOR.PATCH-rc.N`), the Go convention for a module in a subdirectory.

1. Make sure CI is green on the commit, including the alignment job.
2. Tag it: `git tag -a go/v0.1.0 -m "Go client v0.1.0"` and push the tag.
3. Check that the Go module mirror serves it: run `go list -m github.com/awslabs/elasticache-integration-client/go@v0.1.0` from a directory outside the repository, with Go's default module download settings.

A published version cannot be changed or removed from the Go module mirror. To withdraw one, release a new version whose `go.mod` has a `retract` directive for it.
