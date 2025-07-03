# Используем официальный образ OpenJDK 17
FROM eclipse-temurin:17-jre-alpine

# Устанавливаем рабочую директорию
WORKDIR /app

# Копируем JAR файл
COPY build/libs/onlyfans-telegram-bot-1.0.0.jar app.jar

# Создаем пользователя для безопасности
RUN addgroup -g 1001 -S appgroup && \
    adduser -u 1001 -S appuser -G appgroup

# Меняем владельца файлов
RUN chown -R appuser:appgroup /app

# Переключаемся на пользователя
USER appuser

# Открываем порт (если потребуется)
EXPOSE 8080

# Запускаем приложение
ENTRYPOINT ["java", "-jar", "app.jar"]
