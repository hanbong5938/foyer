plugins { `java-library` }

dependencies {
    api(project(":foyer-core"))
    api(libs.lettuce)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.awaitility)
}
