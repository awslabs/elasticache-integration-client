plugins {
    java
}

group = "software.amazon.photon"
version = "0.1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.3")

    // AWS SDK v2 for IAM auth (SigV4 presigning) and ExecutionInterceptor integration
    implementation(platform("software.amazon.awssdk:bom:2.25.16"))
    implementation("software.amazon.awssdk:auth")
    implementation("software.amazon.awssdk:regions")
    implementation("software.amazon.awssdk:sdk-core")
    implementation("software.amazon.awssdk:aws-core")
    implementation("software.amazon.awssdk:http-client-spi")

    // Optional: for testing with real SDK clients
    testImplementation("software.amazon.awssdk:dynamodb")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    
    // Open internal JDK modules for MockTlsServer's self-signed certificate generation
    // Required for Java 17+ which enforces strong encapsulation
    jvmArgs(
        "--add-opens", "java.base/sun.security.x509=ALL-UNNAMED",
        "--add-opens", "java.base/sun.security.util=ALL-UNNAMED"
    )
}
