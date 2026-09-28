plugins {
    application
}

repositories {
    maven {
        url = uri(providers.gradleProperty("photonRepository").get())
    }
    mavenCentral()
}

dependencies {
    implementation(
        "software.amazon.photon:photon-java:" +
            providers.gradleProperty("photonVersion").get()
    )
}

application {
    mainClass.set("PhotonConsumerSmoke")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.register("smoke") {
    dependsOn("run")
}
