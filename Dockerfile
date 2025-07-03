# Use official OpenJDK 17 (Debian-based, multi-platform)
FROM eclipse-temurin:17-jre

# Set working directory
WORKDIR /app

# Copy build artifacts
COPY build/libs/onlyfans-telegram-bot-1.0.0-all.jar app.jar

# Set environment variable for Java options (optional)
ENV JAVA_OPTS=""

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
