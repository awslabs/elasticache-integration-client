// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

buildscript {
    repositories {
        gradlePluginPortal()
    }
    dependencies {
        classpath(
            "com.vanniktech.maven.publish:" +
                "com.vanniktech.maven.publish.gradle.plugin:0.34.0"
        )
    }
}

apply<com.vanniktech.maven.publish.MavenPublishPlugin>()

extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension>("mavenPublishing") {
    publishToMavenCentral()
    if (providers.gradleProperty("releaseSigning").isPresent) {
        signAllPublications()
    }

    coordinates(
        project.group.toString(),
        "aws-elasticache-integration-client",
        project.version.toString()
    )

    pom {
        name.set("ElastiCache Integrations Java Client")
        description.set("ElastiCache Integrations for AWS SDK for Java v2 DynamoDB clients.")
        inceptionYear.set("2026")
        url.set("https://github.com/awslabs/elasticache-integration-client")

        licenses {
            license {
                name.set("Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("aws")
                name.set("Amazon Web Services")
                organization.set("Amazon Web Services")
                organizationUrl.set("https://aws.amazon.com")
            }
        }

        scm {
            url.set("https://github.com/awslabs/elasticache-integration-client")
            connection.set(
                "scm:git:https://github.com/awslabs/elasticache-integration-client.git"
            )
            developerConnection.set(
                "scm:git:ssh://git@github.com/awslabs/elasticache-integration-client.git"
            )
        }
    }
}
