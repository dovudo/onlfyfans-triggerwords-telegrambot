#!/bin/bash
#git pull
echo `docker ps`
echo 'Starting rebuild containers'
#docker-compose down
#docker-compose up -d --force-recreate --build
#echo 'Finished'
docker stop onlyfans-telegram-bot
docker rm onlyfans-telegram-bot
docker rmi onlyfans-telegram-bot
docker build -t onlyfans-telegram-bot .
docker run -d -it --name onlyfans-telegram-bot --restart always onlyfans-telegram-bot
