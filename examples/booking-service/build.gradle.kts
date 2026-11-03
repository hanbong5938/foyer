plugins {
    java
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":foyer-spring-boot-starter"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
}
