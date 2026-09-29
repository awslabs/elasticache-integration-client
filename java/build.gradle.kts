import java.util.zip.ZipFile
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.Exec

plugins {
    java
    jacoco
    id("com.diffplug.spotless") version "8.10.3"
    id("net.ltgt.errorprone") version "5.1.1"
}

group = "software.amazon.photon"
version = "0.0.1"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withSourcesJar()
    withJavadocJar()
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
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.3")
    implementation("dev.cel:compiler:0.14.0")
    implementation("dev.cel:runtime:0.14.0")
    implementation(files(iamToolkitJar))

    // AWS SDK v2 for the sourced IAM toolkit and ExecutionInterceptor integration
    implementation(platform("software.amazon.awssdk:bom:2.54.10"))
    implementation("software.amazon.awssdk:auth")
    implementation("software.amazon.awssdk:regions")
    implementation("software.amazon.awssdk:sdk-core")
    implementation("software.amazon.awssdk:aws-core")
    implementation("software.amazon.awssdk:http-client-spi")
    implementation("software.amazon.awssdk:http-auth-spi")
    implementation("software.amazon.awssdk:identity-spi")
    implementation("software.amazon.awssdk:http-auth-aws")
    implementation("software.amazon.awssdk:apache-client")
    implementation("software.amazon.awssdk:netty-nio-client")

    errorprone("com.google.errorprone:error_prone_core:2.50.0")

    // Optional: for testing with real SDK clients
    testImplementation("software.amazon.awssdk:dynamodb")

    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.86") // MockTlsServer certificates
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    dependsOn(buildIamToolkit)
    if (JavaVersion.current().isJava9Compatible) {
        options.release.set(8)
    }
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// The vendored IAM toolkit is a files(...) dependency, so it is not inside the main JAR
// and a generated POM would not declare it. Publishing is out of scope for now.
val verifyJars by tasks.registering {
    description = "Check that the main, sources, and Javadoc JARs contain the expected entries"
    val jars = mapOf(
        tasks.jar to listOf("software/amazon/photon/session/PhotonClient.class", "idempotent-operations.json"),
        tasks.named<Jar>("sourcesJar") to listOf("software/amazon/photon/session/PhotonClient.java"),
        tasks.named<Jar>("javadocJar") to listOf("index.html")
    )
    jars.keys.forEach { inputs.file(it.flatMap { jar -> jar.archiveFile }) }
    doLast {
        jars.forEach { (jarTask, expected) ->
            val jarFile = jarTask.get().archiveFile.get().asFile
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
