import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.publish.maven.MavenPublication

plugins {
    `java-gradle-plugin`
    `maven-publish`
    id("com.gradleup.shadow") version "9.6.1"
}

group = "me.methodial"
version = project.findProperty("plugin_version") as String? ?: "0.1.0"
// Lowercase artifactId: GitHub Packages rejects uppercase Maven coordinates (422).
base {
    archivesName.set("besided-gradle-plugin")
}

repositories {
    mavenCentral()
}

val shaded = configurations.create("shaded")
configurations.named("implementation") {
    extendsFrom(shaded)
}

dependencies {
    add("shaded", "org.ow2.asm:asm:9.10.1")
    add("shaded", "org.ow2.asm:asm-commons:9.10.1")
    add("shaded", "org.ow2.asm:asm-util:9.10.1")

    compileOnly(gradleApi())

    testImplementation(gradleTestKit())
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    plugins {
        create("besidedPlugin") {
            id = "me.methodial.besided"
            implementationClass = "me.methodial.besided.gradle.BesidedPlugin"
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    configurations = listOf(shaded)
    relocate("org.objectweb.asm", "me.methodial.besided.shadow.asm")
    relocate("org.ow2.asm", "me.methodial.besided.shadow.asm")
    exclude("META-INF/maven/**")
    exclude("module-info.class")
}

configurations.named("apiElements") {
    outgoing.artifacts.clear()
    outgoing.artifact(tasks.named("shadowJar"))
}
configurations.named("runtimeElements") {
    outgoing.artifacts.clear()
    outgoing.artifact(tasks.named("shadowJar"))
}

publishing {
    publications {
        // Single publication: java-gradle-plugin's `pluginMaven` carries the shadow jar
        // (via the apiElements/runtimeElements swap above) and feeds the plugin-marker
        // publication. Explicit lowercase coordinates — GPR rejects uppercase (422).
        // Note: create (not named) — the java-gradle-plugin registers its own
        // pluginMaven wiring lazily and picks up an existing publication via
        // maybeCreate. This mirrors the Groovy DSL's create semantics.
        create<MavenPublication>("pluginMaven") {
            groupId = "me.methodial"
            artifactId = "besided-gradle-plugin"
        }
    }
    repositories {
        // GitHub Packages (team-froglight/besided). Auth via gpr.user/gpr.key in
        // ~/.gradle/gradle.properties, or the GITHUB_ACTOR/GITHUB_TOKEN environment
        // variables (CI). `publishToMavenLocal` stays available for local testing
        // without any credentials.
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

tasks.named("build") {
    dependsOn("shadowJar")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
