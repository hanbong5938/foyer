plugins {
    alias(libs.plugins.spring.boot) apply false
}

allprojects {
    group = "dev.foyer"
    version = "0.1.0-SNAPSHOT"
    repositories { mavenCentral() }
}

subprojects {
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
        }
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(17)
            options.encoding = "UTF-8"
            options.compilerArgs.add("-parameters")
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
        }
        dependencies {
            "implementation"(platform(rootProject.libs.spring.boot.bom))
            "testImplementation"(rootProject.libs.junit.jupiter)
            "testImplementation"(rootProject.libs.assertj)
            "testRuntimeOnly"(rootProject.libs.junit.launcher)
        }
    }
    plugins.withId("java-library") {
        apply(plugin = "maven-publish")
        extensions.configure<PublishingExtension> {
            publications.create<MavenPublication>("maven") {
                from(components["java"])
                pom {
                    name.set(project.name)
                    description.set("Foyer: virtual waiting room for Spring Boot")
                    licenses { license { name.set("Apache-2.0") } }
                }
            }
        }
    }
}
