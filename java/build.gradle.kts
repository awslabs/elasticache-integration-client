import org.gradle.api.tasks.compile.JavaCompile

plugins {
    java
}

group = "software.amazon.photon"
version = "0.1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.3")
    implementation("dev.cel:compiler:0.14.0")
    implementation("dev.cel:runtime:0.14.0")

    // AWS SDK v2 for IAM auth (SigV4 presigning) and ExecutionInterceptor integration
    implementation(platform("software.amazon.awssdk:bom:2.25.16"))
    implementation("software.amazon.awssdk:auth")
    implementation("software.amazon.awssdk:regions")
    implementation("software.amazon.awssdk:sdk-core")
    implementation("software.amazon.awssdk:aws-core")
    implementation("software.amazon.awssdk:http-client-spi")
    implementation("software.amazon.awssdk:apache-client")

    // Optional: for testing with real SDK clients
    testImplementation("software.amazon.awssdk:dynamodb")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    if (JavaVersion.current().isJava9Compatible) {
        options.release.set(8)
    }
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
