plugins {
    application
}

repositories {
    maven {
        url = uri(providers.gradleProperty("cacheRepository").get())
    }
    mavenCentral()
}

dependencies {
    implementation(
        "software.amazon.elasticacheintegrations:elasticache-integrations:" +
            providers.gradleProperty("cacheVersion").get()
    )
}

application {
    mainClass.set("ElastiCacheIntegrationsConsumerSmoke")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.register("smoke") {
    dependsOn("run")
}
