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

## Releasing

Java releases are built from annotated `vMAJOR.MINOR.PATCH` or `vMAJOR.MINOR.PATCH-rc.N` tags. The tag is the release version source of truth: the workflow passes it to Gradle as `releaseVersion`, and the checked-in version stays at its local development baseline. A manual workflow run can take a version for a non-publishing dry run.

The `Java Release` workflow first tests Java 8, 11, 17 and 21. The publishing job then runs in the approval-protected `release` environment: it builds the main, sources and Javadoc JARs once, checks their contents, and runs clean Gradle and Maven consumer smoke tests before it signs and publishes anything.

Configure these secrets on the `release` GitHub environment:

- `MAVEN_CENTRAL_USERNAME`
- `MAVEN_CENTRAL_PASSWORD`
- `MAVEN_SIGNING_KEY` (ASCII-armored private PGP key)
- `MAVEN_SIGNING_PASSWORD`

After Maven Central propagation, the workflow resolves the published version from clean consumers and attaches the published JARs and POM to the GitHub release. Publication, propagation verification and GitHub release creation are separate jobs, so a failure after publishing can be retried without republishing an existing version. A manual workflow run performs the same test, packaging and consumer smoke stages without entering the release environment or publishing to Maven Central.
