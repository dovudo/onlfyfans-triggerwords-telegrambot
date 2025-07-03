# ---- Build Stage ----
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

# Copy Gradle wrapper and build files
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .

# Copy source code
COPY src src

# Build the fat JAR
RUN ./gradlew shadowJar --no-daemon

# ---- Run Stage ----
FROM eclipse-temurin:17-jre
WORKDIR /app

# Copy the fat JAR from the build stage
COPY --from=build /app/build/libs/onlyfans-telegram-bot-1.0.0-all.jar app.jar

# Set environment variable for Java options (optional)
ENV JAVA_OPTS=""

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
