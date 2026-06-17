# Build stage — Maven + JDK 21 (upgrade tag to 26 once eclipse-temurin:26-jdk is available in your registry)
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
# Cache dependency layer separately from source
COPY pom.xml .
RUN mvn -B dependency:go-offline -q
COPY src ./src
RUN mvn -B package -DskipTests -q

# Runtime stage — slim JRE
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /build/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
