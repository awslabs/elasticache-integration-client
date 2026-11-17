// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

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
        "software.amazon.elasticache:aws-elasticache-integration-client:" +
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
