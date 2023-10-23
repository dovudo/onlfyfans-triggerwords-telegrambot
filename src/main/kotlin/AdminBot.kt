
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class AdminBot : TelegramLongPollingBot("6860199290:AAG0Co2whIh_yXOmduk50kLx8sce7BpB8XE") {

    // База данных для хранения админов
    val adminsKeysMap = mutableMapOf<Long, String>()
    val adminTriggers = mutableMapOf<Long, List<String>>()
    val adminOnlyFansConnectorSession = mutableMapOf<Long, OnlyFansConnector>()
    // База данных для хранения ответов пользователей
    private val userResponses: MutableMap<Long, String> = mutableMapOf()
    private val defaultTriggerWords = "http whatsapp whatapp https :// snapchat ".split(",", ", ", " ", ";", "; ")
    private val helpInfoTextFile = File("helpText.txt")

    override fun onUpdateReceived(update: Update) {

        if (update.hasMessage()) {
            val message = update.message
            println("User: ${message.chatId} Message: ${update.message.text}")

            if (message.hasText()) {
                val text = message.text
                val chatId = message.chatId
                //adding new admin
                if (text.startsWith("/token")) {
                    addNewAdminToken(text, chatId)
                } else if (text.startsWith("/start")) {
                    handleStartCommand(chatId)
                } else if (text.startsWith("/help")) {
                    helpInfo(chatId)
                } else if (text.startsWith("/triggers")) {
                    handleTriggers(chatId, message.text)
                } else if (text.startsWith("/close")) {
                    adminOnlyFansConnectorSession[chatId]!!.close()
                    adminOnlyFansConnectorSession.remove(chatId)
                    sendTextMessage(chatId, "Сессия закрыта")
                } else {
                    handleUserResponse(chatId, text)
                }
            }
        }
    }

    private fun helpInfo(chatId: Long) {
        sendTextMessage(chatId, helpInfoTextFile.readText())
    }

    private fun addNewAdminToken(text: String, chatId: Long) {

        val token = text.removePrefix("/token ").ifEmpty { throw Exception("Token not found") }
        adminsKeysMap[chatId] = token
        val onlyFansConnector =
            OnlyFansConnector(chatId, token, this::sendTextMessage, this::processMessageTriggerWords)
        onlyFansConnector.start()
        adminOnlyFansConnectorSession[chatId] = onlyFansConnector
        sendTextMessage(chatId, "Токен успешно добавлен")
    }

    fun handleTriggers(chatId: Long, msg: String) {
        if (msg == "/triggers") {
            sendTextMessage(chatId, "Текущие триггеры: " + (adminTriggers[chatId] ?: defaultTriggerWords).toString())
        } else {
            val words = defaultTriggerWords + msg.lowercase().split(",", ", ", " ", ";", "; ").toList()
            adminTriggers[chatId] = words
            sendTextMessage(chatId, "Установлены новые триггер слова: $words")
        }
    }

    private fun handleStartCommand(chatId: Long) {
        // Обработка команды /start от пользователя
        sendTextMessage(
            chatId,
            "Добро пожаловать! Пожалуйста, авторизуйтесь через персональный токен. Команда: /token [WS token] или командой /help для информации"
        )
    }

    private fun handleUserResponse(chatId: Long, response: String) {
        // Обработка ответа от пользователя
        userResponses[chatId] = response
        sendTextMessage(chatId, "Для информации напишите команду /help")
    }

    private fun processMessageTriggerWords(chatId: Long, message: String) {
        val triggerWords = adminTriggers[chatId] ?: defaultTriggerWords
        val foundWordList = triggerWords.filter { word -> word.contains(message.lowercase()) }.toList()
        if (foundWordList.isNotEmpty())
            sendTextMessage(chatId, "ТРИГГЕР! Время: ${LocalDateTime.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.RFC_1123_DATE_TIME)} сообщение: $message")
    }

    private fun sendTextMessage(chatId: Long, text: String) {
        val sendMessage = SendMessage()
        sendMessage.chatId = chatId.toString()
        sendMessage.text = text
        try {
            execute(sendMessage)
        } catch (e: TelegramApiException) {
            e.printStackTrace()
        }
    }

    override fun getBotUsername(): String {
        return "YourBotName"
    }

    fun seedAdmin() {
        addNewAdminToken(
            "/token eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.eyJ1aWQiOjIzNTMwNjI3MCwib25saW5lIjp0cnVlLCJzdWIiOiJ3cyIsIm5iZiI6MTY5NzgxMzQyNywiZXhwIjoxNjk3ODk5ODI3fQ.CuDQmT2kM7zGAaSJ2Ub-9xC3wFFxNjs-nxZ1JJYY_OxNgqxi1BrHQHDVtKTVWjBysVMXVd6cNj6dAvKrdmEhZ010-0U0JWY0kK_D8riXJt33ye-65bH3PL3ubN6SFaxfnbfJJuKE7SQ8N0um1Q7OFBeIwrd4sOVb4iqAvg2sUMg52g7eD16ukuoe5qXFh3Zeb5Ych8l0_55V_hbG9MkLNf59-POPcrc5JmFC4ATdgPhfbTIRiG2kAtmwHvzIkgphfFy7gEf_HVnU8M4D_C2b8MfPT6vHPwfb3WTjBlZjhUwTWXmlDhc27vvtJI70pJGQjC-_BQYhgyLnyTSt41x4kA",
            206498046
        )
    }
}