plugins {
    java
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(libs.spring.cloud.bom))
    implementation(project(":foyer-gateway"))
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
}
