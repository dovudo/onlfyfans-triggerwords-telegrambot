#!/bin/bash

# OnlyFans Telegram Bot Launcher
echo "Starting OnlyFans Telegram Bot..."

# Проверяем наличие Java
if ! command -v java &> /dev/null; then
    echo "Error: Java is not installed or not in PATH"
    exit 1
fi

# Проверяем версию Java
JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 17 ]; then
    echo "Error: Java 17 or higher is required. Current version: $JAVA_VERSION"
    exit 1
fi

# Собираем проект если нужно
if [ ! -f "build/libs/onlyfans-telegram-bot-1.0.0.jar" ]; then
    echo "Building project..."
    ./gradlew build
fi

# Запускаем приложение
echo "Launching bot..."
java -jar build/libs/onlyfans-telegram-bot-1.0.0.jar 