import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    `java-library`
}

group = "software.amazon.photon"
version = "0.0.1"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

repositories {
    mavenCentral()
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
    api(platform("software.amazon.awssdk:bom:2.54.10"))
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

    // Optional: for testing with real SDK clients
    testImplementation("software.amazon.awssdk:dynamodb")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
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

tasks.test {
    useJUnitPlatform()

    if (JavaVersion.current().isJava9Compatible) {
        // Required for MockTlsServer's reflective certificate generation on Java 17+.
        jvmArgs(
            "--add-opens", "java.base/sun.security.x509=ALL-UNNAMED",
            "--add-opens", "java.base/sun.security.util=ALL-UNNAMED"
        )
    }
}

tasks.register("printVersion") {
    description = "Print the Maven publication version"
    doLast {
        println(project.version)
    }
}

if (providers.gradleProperty("releasePublishing").isPresent) {
    if (!JavaVersion.current().isJava11Compatible) {
        throw GradleException("Release publishing requires JDK 11 or newer")
    }
    apply(from = "release.gradle.kts")
}
