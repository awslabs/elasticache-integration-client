# Contributing

Each client's `DEVELOPER.md` explains how to build, test and release it: [Python](python/DEVELOPER.md), [Node.js](node/DEVELOPER.md), [Java](java/DEVELOPER.md). This page covers what they share.

## Repository layout

```text
.
├── node/    # Node.js client
├── java/    # Java client
├── python/  # Python client
└── shared/  # Language-neutral client contracts
```

## How the clients handle failures

Retries work as they do without the cache: the SDK's retry settings decide
whether and how many times a request is retried. If a request times out or loses
its connection to the cache, the SDK can retry it, for any operation. If the
cache returns `ORIGIN_ERROR` or `INTERNAL_ERROR` on a healthy connection,
the SDK can retry the request. Other errors from the cache are not retried.

When a routed request has not reached the cache because the connection is down,
it may use the configured origin fallback window, including for writes.
`AUTH_ERROR` closes the stale connection and safely passes the rejected
request through while reconnecting. With the fallback set to 0, the rejected
request raises `ElastiCacheIntegrationsCacheUnavailable` at once instead. Other
errors from the cache are not retried and do not trigger origin fallback.

## Shared test cases

`shared/protocol-vectors.json` holds wire vectors: the exact RESP bytes each
client must send for a given request or config, and the response or error type
each client must produce for a given reply. Every client's test suite runs all
of them, and Python, the reference client, defines the expected values. The
Node and Java vector tests list the vectors they do not match yet in
`KNOWN_GAPS`, each with a fragment of the failure message it produces today. A
listed vector must keep failing with that message until it is fixed and
removed.

`shared/sdk-request-cases.json` holds SDK-level cases: a DynamoDB call and the
`aws.request` it must produce. Each client sends the call through its real
AWS SDK into its mock cache and checks the method, URL, and metadata. It also
checks that the body, as parsed JSON, equals the call's `params`, and that no
excluded header is sent. The header names sent must equal the case's
`expected.headers`, and a header whose expected value is `null` is checked by
name only. Each SDK adds or omits a few headers of its own, so each runner
lists those names in `ACCEPTED_HEADER_DIFFERENCES`. An entry that no longer
matches what the SDK sends fails the test.

Local test runs include both sets of tests. CI runs them in a separate
`alignment` job for each changed client and excludes them from the client jobs,
so `CI / alignment / <client>` reports drift from the shared fixtures on its
own.

Each client includes a live DynamoDB E2E example that runs the scenarios
described in [Run the examples against a live cache](#run-the-examples-against-a-live-cache).

## Run the examples against a live cache

The examples use a real DynamoDB table and ElastiCache Serverless cache. They load
configuration from the repository-root `.env`; values already exported in the
shell take precedence.

### 1. Configure credentials and endpoints

From the repository root:

```bash
cp .env.example .env
```

Edit `.env` with the region, cache name, cache endpoint, and DynamoDB table.
The table must have a string partition key named `id`.

If the selected AWS profile uses AWS CLI login credentials, export temporary
credentials so all three SDKs use the same credential chain:

```bash
eval "$(aws configure export-credentials --profile default --format env)"
```

If the cache endpoint is directly reachable, remove
`ELASTICACHE_TUNNEL_HOST` from `.env`. Otherwise, keep it set to `127.0.0.1`
and start the tunnel in a separate terminal. For a tester instance reached
through an EC2 Instance Connect Endpoint:

```bash
set -a
source .env
set +a

INSTANCE_ID="replace-with-instance-id"
INSTANCE_CONNECT_ENDPOINT_ID="replace-with-instance-connect-endpoint-id"
SSH_KEY_FILE="$HOME/.ssh/replace-with-private-key.pem"

ssh \
  -i "$SSH_KEY_FILE" \
  -o StrictHostKeyChecking=accept-new \
  -o ConnectTimeout=30 \
  -o "ProxyCommand=aws ec2-instance-connect open-tunnel --region $AWS_REGION --instance-id %h --instance-connect-endpoint-id $INSTANCE_CONNECT_ENDPOINT_ID" \
  -o ExitOnForwardFailure=yes \
  -N \
  -L "127.0.0.1:${ELASTICACHE_CACHE_PORT}:${ELASTICACHE_CACHE_HOST}:${ELASTICACHE_CACHE_PORT}" \
  "ec2-user@$INSTANCE_ID"
```

Leave this command running while an example runs.
`ELASTICACHE_CACHE_HOST` remains the real cache DNS name for TLS verification while
`ELASTICACHE_TUNNEL_HOST` selects the local TCP destination. Each example applies
this the same way: it resolves only the cache name to the tunnel address,
through `dns.lookup` in Node, `socket.getaddrinfo` in Python, and a JDK
`InetAddressResolverProvider` in Java. No example changes system DNS, so
credential and DynamoDB endpoints keep resolving normally.

### 2. Create the DynamoDB table once

Skip this step when `ELASTICACHE_TABLE_NAME` already exists:

```bash
set -a
source .env
set +a
aws dynamodb create-table \
  --region "$AWS_REGION" \
  --table-name "$ELASTICACHE_TABLE_NAME" \
  --attribute-definitions AttributeName=id,AttributeType=S \
  --key-schema AttributeName=id,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST
aws dynamodb wait table-exists \
  --region "$AWS_REGION" \
  --table-name "$ELASTICACHE_TABLE_NAME"
```

### 3. Run an example

Each run generates a fresh item ID, so the first ElastiCache Integrations read is always a cache
miss and concurrent runs do not interfere. Set `ELASTICACHE_ITEM_ID` only to pin one
ID while reproducing a specific run.

Run one of the following from the repository root.

Node.js:

```bash
(cd node && npm ci && npm run example:local-e2e)
```

Java (sync and async clients). Gradle must run on JDK 21, so set `JAVA_HOME`
to a JDK 21 installation first:

```bash
(cd java && ./gradlew runLocalE2eExample)
(cd java && ./gradlew runLocalE2eAsyncExample)
```

Python:

```bash
(cd python && python -m pip install -e . && python examples/local_e2e.py)
```

Each example runs these scenarios in order and stops at the first failure:

1. Cache hit. The example seeds an item directly in DynamoDB, reads it through
   ElastiCache Integrations, deletes it directly, and confirms with a strongly consistent read
   that DynamoDB no longer has it. A second ElastiCache Integrations read must still return the
   item. If the client bypasses ElastiCache Integrations or falls back to DynamoDB, this fails.
2. Concurrent reads. The example seeds `ELASTICACHE_CONCURRENCY` items (default 16)
   and reads them through ElastiCache Integrations in parallel. Each response must carry its own
   key's item. After deleting the items from DynamoDB, a second parallel round
   must return every item from the cache.
3. Query. A `Query` for a seeded key through ElastiCache Integrations must return that item.
4. Write pass-through. A `PutItem` through the ElastiCache Integrations client must be visible to
   a strongly consistent direct DynamoDB read.
5. Origin error. A `GetItem` on a table that does not exist must surface as the
   SDK's `ResourceNotFoundException`.
6. Large binary item. The example seeds a 380,000-byte random binary attribute
   and a non-ASCII string, just under DynamoDB's 400 KB item limit. ElastiCache Integrations
   must return the item byte for byte on the first read and again after the
   item is deleted from DynamoDB.
7. Batch reads and Scan. `BatchGetItem` and a `Scan` filtered on this run's ID
   prefix must return every seeded item through ElastiCache Integrations.
8. Delete pass-through. A `DeleteItem` and a `BatchWriteItem` (one put, one
   delete) through ElastiCache Integrations must be visible to strongly consistent direct
   DynamoDB reads.

The examples do not call `UpdateItem`, `TransactGetItems`, or
`TransactWriteItems`. When they were last checked, the cache routed them and
DynamoDB rejected them with `UnrecognizedClientException`. Every later request
on the same connection then failed with `AUTH_ERROR`.

The example deletes every item it wrote before exiting.

### 4. Match a failure to its cause

These failures come from the setup, not from the client:

- `HELLO 3 rejected: WRONGPASS` means that the cache rejected the IAM token.
  The client signed it, but with the wrong region or with expired credentials:
  - Values exported in the shell take precedence over `.env`, so check
    `echo $AWS_REGION` against the cache's region.
  - Credentials exported in step 1 expire, and `$AWS_CREDENTIAL_EXPIRATION`
    shows when. Exporting again does not refresh them, because the AWS CLI reads
    the expired variables first. Unset `AWS_ACCESS_KEY_ID`,
    `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN`, and
    `AWS_CREDENTIAL_EXPIRATION`, then run the step 1 command again.
- `Opening Cache connection timed out` from Java, with no other error, usually means
  that Java's default credential chain found no credentials. It falls through
  to EC2 instance metadata, which does not answer outside EC2. To read an
  AWS CLI login profile, the AWS SDK for Java needs the optional
  `software.amazon.awssdk:signin` module on the classpath, in any application.
  The examples include it. Without it, the SDK skips the profile silently.
  Exporting credentials as shown in step 1 also works.
- `MissingDependencyException: Using the login credential provider requires an
  additional dependency` from Python means that boto3 is reading AWS CLI login
  credentials directly. botocore needs its optional `crt` extra for that, in
  any application. Run `pip install "botocore[crt]"`, or export
  credentials as shown in step 1.
- `Gradle requires JVM 17 or later to run` means that `JAVA_HOME` points at an
  older JDK. Point it at JDK 21.
- A TLS certificate error means that `ELASTICACHE_CACHE_HOST` is an IP address
  or `localhost`. Set it to the cache endpoint's DNS name and put the tunnel
  address in `ELASTICACHE_TUNNEL_HOST`.

Node prints `@aws-sdk/core/protocols - WARN - JsonCodec2: you have called a
string method on a Uint8Array request body` once a minute. The client causes
it, the request still succeeds, and a fix is tracked separately.
