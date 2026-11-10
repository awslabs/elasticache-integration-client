# Live validation sample

Runs the ElastiCache Integrations client against a real cache and compares every result with a plain
`AmazonDynamoDBClient`. It prints one `PASS` or `FAIL` line per check and exits 0 only when all
pass. It writes only items whose key starts with `dotnet-live-<run>-`,
and deletes them at the end.

Settings come from the environment:

| Variable | Meaning |
|----------|---------|
| `ELASTICACHE_INTEGRATIONS_ENDPOINT` | The cache endpoint, `host:port` |
| `ELASTICACHE_INTEGRATIONS_CACHE_NAME` | The cache name |
| `ELASTICACHE_INTEGRATIONS_TABLE` | A table with a String hash key `pk`. Default `elasticache-integrations-test` |
| `AWS_REGION` | The region of the cache and the table |
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN` | Credentials with `elasticache:Connect` and DynamoDB read and write on the table |

```sh
dotnet run --project samples/Amazon.ElastiCache.Integrations.LiveValidation -- ops
```

The cache is reachable only from its VPC. For a host without .NET, publish a self-contained
build and copy it there:

```sh
dotnet publish samples/Amazon.ElastiCache.Integrations.LiveValidation -c Release -p:LiveRid=linux-arm64 -o out
```

| Mode | Checks |
|------|--------|
| `ops` | The IAM token is accepted, also for an upper-case cache name. The ten operations match DynamoDB and are routed through the cache, with no call from the client to DynamoDB. A stale read proves a hit, and each write invalidates. `DescribeTable` passes through. The cache config is applied and fully usable, and `HELLO 3` is accepted |
| `auth` | With re-AUTH every 15 s, the connection keeps serving misses with no reconnect. A re-AUTH token with a bad signature is refused with `WRONGPASS`, and misses are still routed through the cache with the credential it already holds. Re-AUTH then recovers |
| `push` | `HELLO 3` is accepted. A cache hit started 1 ms after a miss on a 200 KiB item, on the same connection, finishes first in most trials. The miss is assumed to be written within that 1 ms (a whole hit round trip takes about 1 ms); then, with replies in order, the hit could not finish first, so the miss's reply came as an async push |
| `fas` | Writes one item per poll up front, then reads one through the cache every 15 s (`ELASTICACHE_INTEGRATIONS_FAS_POLL_SECONDS`) for up to 80 minutes (`ELASTICACHE_INTEGRATIONS_FAS_MINUTES`). There is no re-AUTH unless `ELASTICACHE_INTEGRATIONS_FAS_REAUTH_MINUTES` is set (under 720). Reports when a miss first fails: the cache relays DynamoDB's `ExpiredTokenException`, or answers `AUTH_ERROR` (event 1103). Either marks the end of its credential for the client. It polls 5 more minutes after the first failure. Polling needs no credentials of its own, so it can run past their expiry; `ELASTICACHE_INTEGRATIONS_CREDENTIALS_EXPIRE` is only logged |
| `cleanup` | Deletes the items of the earlier run named by `ELASTICACHE_INTEGRATIONS_RUN`, which it requires: for a run whose own cleanup failed |
