import org.gradle.api.publish.maven.MavenPublication

// What code built with besided compiles against: the annotations and the Sided helper. The
// plugin carries this jar inside it and adds it to every project that applies it, so a
// consumer needs no repository for it; it is also published on its own, for builds that
// want it by coordinates.
plugins {
    `java-library`
    `maven-publish`
}

group = "me.methodial"
version = project.findProperty("plugin_version") as String? ?: "0.1.0"
base {
    archivesName.set("besided-api")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("api") {
            groupId = "me.methodial"
            artifactId = "besided-api"
            from(components["java"])
        }
    }
    repositories {
        // The same GitHub Packages repository and credentials as the plugin.
        maven {
            name = "GitHubPackages"
            setUrl("https://maven.pkg.github.com/team-froglight/besided")
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).getOrElse("")
                password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).getOrElse("")
            }
        }
    }
}
