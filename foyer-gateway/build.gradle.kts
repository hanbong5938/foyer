plugins { `java-library` }

dependencies {
    api(project(":foyer-spring-boot-starter"))
    implementation(platform(libs.spring.cloud.bom))
    compileOnly("org.springframework.cloud:spring-cloud-gateway-server-webflux")

    testImplementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
