import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar

plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.maven.publish) apply false
}

allprojects {
    group = "io.github.hanbong5938"
    version = providers.gradleProperty("foyerVersion").getOrElse("0.1.0-SNAPSHOT")
    repositories { mavenCentral() }
}

subprojects {
    plugins.withType<JavaPlugin> {
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(17)
            options.encoding = "UTF-8"
            options.compilerArgs.add("-parameters")
        }
        tasks.withType<Javadoc>().configureEach {
            (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:none", true)
            (options as StandardJavadocDocletOptions).addStringOption("Xmaxwarns", "1")
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
    // Library modules publish to Maven Central (Sonatype Central Portal). Signing and credentials come from
    // ORG_GRADLE_PROJECT_mavenCentralUsername / mavenCentralPassword / signingInMemoryKey / signingInMemoryKeyPassword.
    plugins.withId("java-library") {
        apply(plugin = "com.vanniktech.maven.publish")
        extensions.configure<MavenPublishBaseExtension> {
            configure(JavaLibrary(javadocJar = JavadocJar.Javadoc(), sourcesJar = SourcesJar.Sources()))
            publishToMavenCentral(automaticRelease = false)
            if (providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent) signAllPublications()
            coordinates(project.group.toString(), project.name, project.version.toString())
            pom {
                name.set(project.name)
                description.set("Foyer: virtual waiting room for Spring Boot (" + project.name + ")")
                url.set("https://github.com/hanbong5938/foyer")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("hanbong5938")
                        name.set("Hanbong Bae")
                        url.set("https://github.com/hanbong5938")
                    }
                }
                scm {
                    url.set("https://github.com/hanbong5938/foyer")
                    connection.set("scm:git:https://github.com/hanbong5938/foyer.git")
                    developerConnection.set("scm:git:ssh://git@github.com/hanbong5938/foyer.git")
                }
            }
        }
    }
}
