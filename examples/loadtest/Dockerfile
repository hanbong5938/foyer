FROM eclipse-temurin:25-jre-alpine
ARG JAR
COPY ${JAR} /app.jar
ENTRYPOINT ["java", "-XX:+UseZGC", "-Xmx512m", "-jar", "/app.jar"]
