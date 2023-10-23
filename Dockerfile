FROM gradle:jdk19-alpine
#FROM container-registry.oracle.com/graalvm/native-image:latest
MAINTAINER onlyfans-telegram-bot
COPY . /usr/share/onlyfans-telegram-bot/
WORKDIR /usr/share/onlyfans-telegram-bot/
#RUN gradle -v
#RUN gradle wrapper
#RUN ./gradlew clean build --stacktrace --warning-mode all --no-daemon -x test
ENTRYPOINT ./gradlew run
#RUN ls -h build/libs
#ENTRYPOINT java -jar build/libs/onlyfans-telegram-bot.jar
