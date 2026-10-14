import java.util.zip.ZipFile
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    `java-library`
    jacoco
    id("com.diffplug.spotless") version "8.10.4"
    id("net.ltgt.errorprone") version "5.1.1"
}

group = "software.amazon.elasticacheintegrations"
version = "0.0.1"

val releasePublishing = providers.gradleProperty("releasePublishing").isPresent

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    if (!releasePublishing) {
        withSourcesJar()
        withJavadocJar()
    }
}

repositories {
    mavenCentral()
}

spotless {
    java {
        target("src/**/*.java")
        importOrder()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
        googleJavaFormat("1.22.0").reflowLongStrings()
        leadingSpacesToTabs(2)
        leadingTabsToSpaces(4)
    }
}

sourceSets {
    main {
        resources {
            srcDir("../shared")
            include("idempotent-operations.json")
        }
    }
    test {
        resources {
            srcDir("../shared")
            include("protocol-vectors.json", "sdk-request-cases.json")
        }
    }
}

val iamToolkitRoot = layout.projectDirectory.dir("vendor/developer-toolkit-elasticache/java")
val iamToolkitJar = iamToolkitRoot.file(
    "target/developer-toolkit-elasticache-0.1.0-SNAPSHOT.jar"
)

val buildIamToolkit by tasks.registering(Exec::class) {
    description = "Build the sourced ElastiCache Java IAM toolkit"
    workingDir(iamToolkitRoot)
    commandLine("mvn", "--quiet", "-DskipTests", "package")
    inputs.files(fileTree(iamToolkitRoot.dir("src/main")))
    inputs.file(iamToolkitRoot.file("pom.xml"))
    outputs.file(iamToolkitJar)
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")
    implementation("dev.cel:compiler:0.14.0")
    implementation("dev.cel:runtime:0.14.0")
    implementation(files(iamToolkitJar))

    // AWS SDK v2 for the sourced IAM toolkit and ExecutionInterceptor integration
    // The compatibility workflow passes -PawsSdkVersion=2.+ to test the latest SDK.
    api(platform("software.amazon.awssdk:bom:${providers.gradleProperty("awsSdkVersion").getOrElse("2.55.6")}"))
    api("software.amazon.awssdk:auth")
    api("software.amazon.awssdk:regions")
    api("software.amazon.awssdk:sdk-core")
    api("software.amazon.awssdk:aws-core")
    api("software.amazon.awssdk:http-client-spi")
    implementation("software.amazon.awssdk:http-auth-spi")
    implementation("software.amazon.awssdk:identity-spi")
    implementation("software.amazon.awssdk:http-auth-aws")
    implementation("software.amazon.awssdk:apache-client")
    implementation("software.amazon.awssdk:netty-nio-client")

    errorprone("com.google.errorprone:error_prone_core:2.50.0")

    // Optional: for testing with real SDK clients
    testImplementation("software.amazon.awssdk:dynamodb")

    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.86") // MockTlsServer certificates
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    dependsOn(buildIamToolkit)
    if (JavaVersion.current().isJava9Compatible) {
        options.release.set(8)
    }
}

tasks.jar {
    dependsOn(buildIamToolkit)

    // TODO(#49): Stop bundling these classes once the toolkit is on Maven Central.
    // The IAM toolkit is sourced rather than published, so include its classes
    // in the distributable JAR. Its AWS SDK dependencies remain normal POM
    // dependencies declared above.
    from({ zipTree(iamToolkitJar.asFile) }) {
        exclude(
            "META-INF/*.SF",
            "META-INF/*.DSA",
            "META-INF/*.RSA",
            "META-INF/MANIFEST.MF",
            "META-INF/LICENSE",
            "META-INF/NOTICE"
        )
    }

    from("../LICENSE") {
        into("META-INF")
        rename { "LICENSE" }
    }
    from("../NOTICE") {
        into("META-INF")
        rename { "NOTICE" }
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// Gradle itself runs on JDK 21. Pass -PtestJavaVersion=<n> to run the tests on another
// installed JDK, which is how CI covers every supported Java version.
val testJavaVersion = providers.gradleProperty("testJavaVersion")

tasks.test {
    useJUnitPlatform()
    if (testJavaVersion.isPresent) {
        javaLauncher.set(javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(testJavaVersion.get().toInt()))
        })
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

if (releasePublishing) {
    if (!JavaVersion.current().isJava11Compatible) {
        throw GradleException("Release publishing requires JDK 11 or newer")
    }
    apply(from = "release.gradle.kts")
}

val sourcesJarTaskName = "sourcesJar"
val javadocJarTaskName = if (releasePublishing) "plainJavadocJar" else "javadocJar"

// Rounded down to the nearest 5 from the 2026-10-01 baseline (line 85.9%, branch 72.8%).
tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.85".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = "0.70".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

val verifyJars by tasks.registering {
    description = "Check that the main, sources, and Javadoc JARs contain the expected entries"
    dependsOn(tasks.jar, sourcesJarTaskName, javadocJarTaskName)
    inputs.file(tasks.jar.flatMap { it.archiveFile })
    inputs.file(providers.provider {
        tasks.named<AbstractArchiveTask>(sourcesJarTaskName).get().archiveFile.get().asFile
    })
    inputs.file(providers.provider {
        tasks.named<AbstractArchiveTask>(javadocJarTaskName).get().archiveFile.get().asFile
    })
    doLast {
        val jars = mapOf(
            tasks.jar.get().archiveFile.get().asFile to listOf(
                "software/amazon/elasticacheintegrations/session/ElastiCacheIntegrationsClient.class",
                "software/amazon/elasticache/auth/ElastiCacheIamAuthTokenProvider.class",
                "idempotent-operations.json",
                "META-INF/LICENSE",
                "META-INF/NOTICE"
            ),
            tasks.named<AbstractArchiveTask>(sourcesJarTaskName).get().archiveFile.get().asFile to
                listOf("software/amazon/elasticacheintegrations/session/ElastiCacheIntegrationsClient.java"),
            tasks.named<AbstractArchiveTask>(javadocJarTaskName).get().archiveFile.get().asFile to
                listOf("index.html")
        )
        jars.forEach { (jarFile, expected) ->
            ZipFile(jarFile).use { zip ->
                expected.forEach { entry ->
                    check(zip.getEntry(entry) != null) { "${jarFile.name} is missing $entry" }
                }
            }
        }
    }
}

tasks.check {
    dependsOn(verifyJars)
}

tasks.register("printVersion") {
    description = "Print the Maven publication version"
    doLast {
        println(project.version)
    }
}
