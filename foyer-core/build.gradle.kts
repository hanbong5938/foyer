plugins { `java-library` }

dependencies {
    api(platform(libs.spring.boot.bom))
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.gson)
}
