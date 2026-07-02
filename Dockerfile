# --- Build stage: compile the Spring Boot jar ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
# Tests are skipped in the image build (they use an H2 file DB with a known file-lock caveat);
# run them locally / in CI, not on every deploy.
RUN mvn clean package -DskipTests -B

# --- Runtime stage: slim JRE running the packaged jar ---
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# Default to the prod profile; the platform (Render) can still override via SPRING_PROFILES_ACTIVE.
ENTRYPOINT ["sh", "-c", "java -Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:-prod} -jar app.jar"]
