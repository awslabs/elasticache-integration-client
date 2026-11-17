# Developing the ElastiCache Integrations Java client

## Building and testing

Gradle must run on JDK 21. The compiler emits Java 8 bytecode (`options.release.set(8)`), so do not use language or library APIs introduced after Java 8.

- `./gradlew build` is the complete local gate: it compiles with Error Prone, checks formatting with Spotless (google-java-format), runs the tests, writes JaCoCo reports to `build/reports/jacoco/test/` and verifies coverage, builds the sources and Javadoc JARs, and checks the JAR contents.
- `./gradlew spotlessApply` fixes formatting.
- `./gradlew test --tests "ClassName"` or `--tests "ClassName.methodName"` runs focused tests.
- Tests run on Java 8, 11, 17 and 21. To run them on another installed JDK, pass `-PtestJavaVersion=<n>`, for example `./gradlew test -PtestJavaVersion=8`.

Two further suites run in CI and can be reproduced locally:

- The AWS SDK compatibility matrix, which builds and tests against a range of SDK versions down to the supported floor. Select one with `-PawsSdkVersion=<version>` and `-PawsSdkRuntimeVersion=<version>`.
- The cross-client alignment tests, which run the fixtures in `shared/` that every client must agree on. Run them with `-PtestIncludeTags=alignment`.

## Live DynamoDB E2E example

Copy the repository-root [`.env.example`](../.env.example) to `.env` and fill it in,
then run from `java/`:

```bash
./gradlew runLocalE2eExample
./gradlew runLocalE2eAsyncExample
```

`runLocalE2eExample` uses `DynamoDbClient` and `runLocalE2eAsyncExample` uses
`DynamoDbAsyncClient`. Both load the repository-root `.env` and run their
scenarios against the cache. The examples run on JDK 21 because
they route `ELASTICACHE_CACHE_HOST` to `ELASTICACHE_TUNNEL_HOST` with the JDK 18+
resolver SPI (`TunnelResolverProvider`); the client itself still targets
Java 8.
Each run uses a freshly generated item ID so the first ElastiCache Integrations read is a cache
miss; set `ELASTICACHE_ITEM_ID` only to pin one ID. Supported overrides include
`ELASTICACHE_CACHE_HOST`, `ELASTICACHE_CACHE_PORT`, `ELASTICACHE_CACHE_NAME`,
`ELASTICACHE_TUNNEL_HOST`, `ELASTICACHE_CACHE_USER_ID`, `ELASTICACHE_REQUEST_TIMEOUT_SECONDS`,
`AWS_REGION` (default `us-east-1`), `ELASTICACHE_TABLE_NAME`, and
`ELASTICACHE_CONCURRENCY`.

## Releasing

Java releases are built from annotated `vMAJOR.MINOR.PATCH` or `vMAJOR.MINOR.PATCH-rc.N` tags. The tag is the release version source of truth: the workflow passes it to Gradle as `releaseVersion`, and the checked-in version stays at its local development baseline. A manual workflow run can take a version for a non-publishing dry run.

The `Java Release` workflow first tests Java 8, 11, 17 and 21. The publishing job then runs in the approval-protected `release` environment: it builds the main, sources and Javadoc JARs once, checks their contents, and runs clean Gradle and Maven consumer smoke tests before it signs and publishes anything.

Configure these secrets on the `release` GitHub environment:

- `MAVEN_CENTRAL_USERNAME`
- `MAVEN_CENTRAL_PASSWORD`
- `MAVEN_SIGNING_KEY` (ASCII-armored private PGP key)
- `MAVEN_SIGNING_PASSWORD`

After Maven Central propagation, the workflow resolves the published version from clean consumers and attaches the published JARs and POM to the GitHub release. Publication, propagation verification and GitHub release creation are separate jobs, so a failure after publishing can be retried without republishing an existing version. A manual workflow run performs the same test, packaging and consumer smoke stages without entering the release environment or publishing to Maven Central.
