import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking


data class WebSocketRequest(val act: String, val token: String)
data class WebSocketResponse(val connected: Boolean)
data class WebSocketEvent(val responseType: String, val text: String)


class OnlyFansConnector(
    private val chatId: Long,
    private val token: String,
    val telegramMessageProvider: (Long, String) -> Unit,
    val processMessageTriggerWords: (Long, String) -> Unit
) {

    private val url = "wss://ws2.onlyfans.com/ws2/"
    private val jsonMapper = JsonMapper.builder()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .build()

    private val client = HttpClient(CIO) {
        install(WebSockets) {
            pingInterval = 20_000
        }
    }

    fun start() {
        try {
            runBlocking {
                // Отправляем аутентификационный запрос
                val request = WebSocketRequest("connect", token)

                client.webSocket(url) {
                    outgoing.send(Frame.Text(jsonMapper.writeValueAsString(request)))
                    //sendSerialized(request)
                    val connectionStatus = incoming.receive() as? Frame.Text
                    println(connectionStatus?.readText())
                    if (connectionStatus!!.readText() == "{\"connected\": true, \"v\": \"4.1\"}") {
                        telegramMessageProvider(chatId, "Подключение успешно")
                    }else{
                        throw RuntimeException("Подключение не удалось")
                    }
                    for (frame in incoming) {
                        (frame as? Frame.Text)?.let {
                            val othersMessage = frame.readText()
                            println("Received new message: $othersMessage")
                            handleNewMessage(othersMessage)
                        }
                    }
                    heartBeat(this)
                }
            }
        } catch (e: Exception) {
            println("Error: $e")
            handleError( "Ошибка: ${e.message}, Попробуйте обновить токен", e)
        }
    }

    private fun handleError(textError: String, exception: Exception) {
        println("Error: $exception")
        telegramMessageProvider(chatId, "Произошла ошибка : $textError")
    }

    private fun handleNewMessage(message: String) {

        val jsonNode = jsonMapper.readTree(message)
        val responseType = jsonNode.findValue("responseType")
        if (responseType != null && responseType.asText() == "message") {
            val text = jsonNode.findValue("text").asText()
            println("Получено сообщение: $text")
            processMessageTriggerWords(chatId, text)
        }
    }

    private fun heartBeat(defaultClientWebSocketSession: DefaultClientWebSocketSession) {
        runBlocking {
            while (true) {
                val heartBeatRequest = WebSocketRequest("get_onlines", "[1]")
                defaultClientWebSocketSession.send(Frame.Text(jsonMapper.writeValueAsString(heartBeatRequest)))
                delay(30000) // Задержка 30 секунд*/
            }
        }
    }

    fun close() {
        client.close()
    }
}