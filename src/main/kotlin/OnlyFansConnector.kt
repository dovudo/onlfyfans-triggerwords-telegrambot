import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter


data class WebSocketRequest(val act: String, val token: String)
data class WebSocketResponse(
    val connected: Boolean? = null,
    val v: String? = null
)
data class WebSocketEvent(val responseType: String, val text: String)


class OnlyFansConnector(
    private val chatId: Long,
    private val accountName: String,
    private val token: String,
    val telegramMessageProvider: (Long, String) -> Unit,
    val processMessageTriggerWords: (Long, String, String) -> Unit,
    val updateUserInfo: (Long, String, AdminBot.UserInfo) -> Unit
) {

    private val url = "wss://ws2.onlyfans.com/ws3/24"
    private val jsonMapper = JsonMapper.builder()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .build()

    private val client = HttpClient(CIO) {
        install(WebSockets) {
            pingInterval = 20_000
        }
    }

    private var isConnected = false
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private val reconnectDelayMs = 5000L // 5 seconds between attempts

    fun start() {
        println("[${getTimestamp()}] 🔌 Starting connection to OnlyFans WebSocket for user $chatId")
        println("[${getTimestamp()}] 🌐 URL: $url")
        println("[${getTimestamp()}] 🔑 Token: ${token.take(20)}...")
        
        connectWithRetry()
    }

    private fun connectWithRetry() {
        while (reconnectAttempts < maxReconnectAttempts && !isConnected) {
            try {
                if (reconnectAttempts > 0) {
                    println("[${getTimestamp()}] 🔄 Reconnection attempt #$reconnectAttempts of $maxReconnectAttempts")
                    telegramMessageProvider(chatId, "🔄 Reconnection attempt #$reconnectAttempts of $maxReconnectAttempts...")
                    Thread.sleep(reconnectDelayMs)
                }
                
                attemptConnection()
                
            } catch (e: Exception) {
                reconnectAttempts++
                println("[${getTimestamp()}] ❌ Connection error (attempt $reconnectAttempts): ${e.message}")
                
                if (reconnectAttempts >= maxReconnectAttempts) {
                    val errorMessage = "❌ Failed to connect after $maxReconnectAttempts attempts. Please check your token and try again."
                    telegramMessageProvider(chatId, errorMessage)
                    println("[${getTimestamp()}] 💀 Maximum number of connection attempts exceeded")
                }
            }
        }
    }

    private fun attemptConnection() {
        runBlocking {
            // Send authentication request
            val request = WebSocketRequest("connect", token)
            val requestJson = jsonMapper.writeValueAsString(request)
            
            println("[${getTimestamp()}] 📤 Sending connection request: $requestJson")

            client.webSocket(url) {
                println("[${getTimestamp()}] ✅ WebSocket connection established")
                
                // Send connection request
                outgoing.send(Frame.Text(requestJson))
                println("[${getTimestamp()}] 📤 Request sent")
                
                // Receive connection status response
                val connectionStatus = incoming.receive() as? Frame.Text
                val statusText = connectionStatus?.readText() ?: "null"
                println("[${getTimestamp()}] 📥 Server response received: $statusText")
                
                // Parse response
                try {
                    val response = jsonMapper.readValue(statusText, WebSocketResponse::class.java)
                    println("[${getTimestamp()}] 🔍 Parsed response: connected=${response.connected}, v=${response.v}")
                    
                    if (response.connected == true) {
                        isConnected = true
                        reconnectAttempts = 0 // Reset counter on successful connection
                        println("[${getTimestamp()}] ✅ Successfully connected to OnlyFans!")
                        telegramMessageProvider(chatId, "✅ Successfully connected to OnlyFans! API version: ${response.v}")
                        
                        // Start processing messages
                        println("[${getTimestamp()}] 🎧 Listening for messages...")
                        try {
                            for (frame in incoming) {
                                (frame as? Frame.Text)?.let {
                                    val message = frame.readText()
                                    println("[${getTimestamp()}] 📨 Message received: $message")
                                    handleNewMessage(message)
                                }
                            }
                        } catch (e: Exception) {
                            println("[${getTimestamp()}] ❌ Error while listening for messages: "+e.message)
                            isConnected = false
                            throw e // Will trigger reconnection
                        }
                        
                    } else {
                        println("[${getTimestamp()}] ❌ Server rejected connection")
                        throw RuntimeException("Server rejected connection: $statusText")
                    }
                } catch (e: Exception) {
                    println("[${getTimestamp()}] ❌ Error parsing response: ${e.message}")
                    // Try simple string check
                    if (statusText.contains("\"connected\": true")) {
                        isConnected = true
                        reconnectAttempts = 0
                        println("[${getTimestamp()}] ✅ Connection confirmed by string check")
                        telegramMessageProvider(chatId, "✅ Successfully connected to OnlyFans!")
                        
                        // Start processing messages
                        println("[${getTimestamp()}] 🎧 Listening for messages...")
                        try {
                            for (frame in incoming) {
                                (frame as? Frame.Text)?.let {
                                    val message = frame.readText()
                                    println("[${getTimestamp()}] 📨 Message received: $message")
                                    handleNewMessage(message)
                                }
                            }
                        } catch (e: Exception) {
                            println("[${getTimestamp()}] ❌ Error while listening for messages: "+e.message)
                            isConnected = false
                            throw e // Will trigger reconnection
                        }
                        
                    } else {
                        throw RuntimeException("Error parsing server response: $statusText")
                    }
                }
            }
        }
    }

    fun reconnect() {
        println("[${getTimestamp()}] 🔄 Reconnection requested")
        isConnected = false
        reconnectAttempts = 0
        connectWithRetry()
    }

    fun close() {
        println("[${getTimestamp()}] 🔒 Closing OnlyFans connection")
        isConnected = false
        try {
            client.close()
        } catch (e: Exception) {
            println("[${getTimestamp()}] ⚠️ Error while closing client: ${e.message}")
        }
    }

    private fun handleError(textError: String, exception: Exception) {
        println("[${getTimestamp()}] ❌ Handling error: $textError")
        println("[${getTimestamp()}] 📋 Error details: ${exception.message}")
        telegramMessageProvider(chatId, "❌ An error occurred: $textError")
    }

    private fun handleNewMessage(message: String) {
        try {
            println("[${getTimestamp()}] 🔍 Processing message: $message")
            
            val jsonNode = jsonMapper.readTree(message)
            
            // Check different event types
            when {
                // Messages with api2_chat_message
                jsonNode.has("api2_chat_message") -> {
                    handleChatMessage(jsonNode.get("api2_chat_message"))
                }
                // Tips
                jsonNode.has("tip") -> {
                    handleTipEvent(jsonNode.get("tip"))
                }
                // New subscriptions
                jsonNode.has("subscription") -> {
                    handleSubscriptionEvent(jsonNode.get("subscription"))
                }
                // Paid messages opened
                jsonNode.has("purchase") -> {
                    handlePurchaseEvent(jsonNode.get("purchase"))
                }
                // System notifications (ignore)
                jsonNode.has("chat_messages") || jsonNode.has("typing") || jsonNode.has("messages") -> {
                    println("[${getTimestamp()}] 🔇 Ignoring system notification")
                }
                // Other message types
                else -> {
                    println("[${getTimestamp()}] ❓ Unknown message type")
                }
            }
        } catch (e: Exception) {
            println("[${getTimestamp()}] ❌ Error processing message: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun handleChatMessage(messageNode: com.fasterxml.jackson.databind.JsonNode) {
        val responseType = messageNode.findValue("responseType")?.asText()
        val rawText = messageNode.findValue("text")?.asText()
        
        if (responseType == "message" && rawText != null) {
            val cleanText = cleanHtmlTags(rawText)
            println("[${getTimestamp()}] 💬 Text message received: $rawText")
            println("[${getTimestamp()}] 🧹 Cleaned text: $cleanText")
            
            // Save user info
            val fromUser = messageNode.findValue("fromUser")
            if (fromUser != null) {
                val userInfo = AdminBot.UserInfo(
                    id = fromUser.findValue("id")?.asLong() ?: 0L,
                    name = fromUser.findValue("name")?.asText() ?: "Unknown",
                    username = fromUser.findValue("username")?.asText() ?: "unknown",
                    isVerified = fromUser.findValue("isVerified")?.asBoolean() ?: false,
                    subscribePrice = fromUser.findValue("subscribePrice")?.asInt() ?: 0,
                    lastSeen = fromUser.findValue("lastSeen")?.asText() ?: "Unknown",
                    canEarn = fromUser.findValue("canEarn")?.asBoolean() ?: false
                )
                updateUserInfo(chatId, accountName, userInfo)
            }
            
            // Check for links or payment systems
            if (containsLinksOrPaymentSystems(cleanText)) {
                if (fromUser != null) {
                    val userId = fromUser.findValue("id")?.asLong()
                    val name = fromUser.findValue("name")?.asText()
                    val username = fromUser.findValue("username")?.asText()
                    val isVerified = fromUser.findValue("isVerified")?.asBoolean()
                    
                    val notification = buildString {
                        appendLine("🚨 <b>IMPORTANT MESSAGE!</b>")
                        appendLine("👤 From: <code>$name</code> (@$username)")
                        appendLine("🆔 ID: <code>$userId</code>")
                        appendLine("✅ Verified: ${if (isVerified == true) "Yes" else "No"}")
                        appendLine("🔗 Contains links or payment systems!")
                        appendLine("\n📝 <b>Message text:</b>")
                        appendLine("<code>$cleanText</code>")
                    }
                    
                    telegramMessageProvider(chatId, notification)
                    println("[${getTimestamp()}] 🚨 Sent important message notification")
                }
            }
            
            // Check trigger words for regular messages
            processMessageTriggerWords(chatId, cleanText, accountName)
        }
    }

    private fun handleTipEvent(tipNode: com.fasterxml.jackson.databind.JsonNode) {
        println("[${getTimestamp()}] 💝 Processing tip event")
        
        val amount = tipNode.findValue("amount")?.asInt()
        val currency = tipNode.findValue("currency")?.asText() ?: "USD"
        val fromUser = tipNode.findValue("fromUser")
        
        if (fromUser != null) {
            val userId = fromUser.findValue("id")?.asLong()
            val name = fromUser.findValue("name")?.asText()
            val username = fromUser.findValue("username")?.asText()
            val isVerified = fromUser.findValue("isVerified")?.asBoolean()
            
            val notification = buildString {
                appendLine("💝 <b>NEW TIP!</b>")
                appendLine("👤 From: <code>$name</code> (@$username)")
                appendLine("🆔 ID: <code>$userId</code>")
                appendLine("✅ Verified: ${if (isVerified == true) "Yes" else "No"}")
                appendLine("💰 Amount: <code>$$amount $currency</code>")
            }
            
            telegramMessageProvider(chatId, notification)
            println("[${getTimestamp()}] 💝 Sent tip notification: $${amount} $currency")
        }
    }

    private fun handleSubscriptionEvent(subscriptionNode: com.fasterxml.jackson.databind.JsonNode) {
        println("[${getTimestamp()}] 🔔 Processing subscription event")
        
        val fromUser = subscriptionNode.findValue("fromUser")
        val subscriptionType = subscriptionNode.findValue("type")?.asText() ?: "new"
        val price = subscriptionNode.findValue("price")?.asInt()
        val currency = subscriptionNode.findValue("currency")?.asText() ?: "USD"
        
        if (fromUser != null) {
            val userId = fromUser.findValue("id")?.asLong()
            val name = fromUser.findValue("name")?.asText()
            val username = fromUser.findValue("username")?.asText()
            val isVerified = fromUser.findValue("isVerified")?.asBoolean()
            
            val notification = buildString {
                appendLine("🔔 <b>NEW SUBSCRIPTION!</b>")
                appendLine("👤 From: <code>$name</code> (@$username)")
                appendLine("🆔 ID: <code>$userId</code>")
                appendLine("✅ Verified: ${if (isVerified == true) "Yes" else "No"}")
                appendLine("📋 Type: <code>$subscriptionType</code>")
                if (price != null && price > 0) {
                    appendLine("💰 Price: <code>$$price $currency</code>")
                }
            }
            
            telegramMessageProvider(chatId, notification)
            println("[${getTimestamp()}] 🔔 Sent subscription notification")
        }
    }

    private fun handlePurchaseEvent(purchaseNode: com.fasterxml.jackson.databind.JsonNode) {
        println("[${getTimestamp()}] 💳 Processing purchase event")
        
        val fromUser = purchaseNode.findValue("fromUser")
        val price = purchaseNode.findValue("price")?.asInt()
        val currency = purchaseNode.findValue("currency")?.asText() ?: "USD"
        val itemType = purchaseNode.findValue("itemType")?.asText() ?: "message"
        
        if (fromUser != null) {
            val userId = fromUser.findValue("id")?.asLong()
            val name = fromUser.findValue("name")?.asText()
            val username = fromUser.findValue("username")?.asText()
            val isVerified = fromUser.findValue("isVerified")?.asBoolean()
            
            val notification = buildString {
                appendLine("💳 <b>PAID MESSAGE OPENED!</b>")
                appendLine("👤 From: <code>$name</code> (@$username)")
                appendLine("🆔 ID: <code>$userId</code>")
                appendLine("✅ Verified: ${if (isVerified == true) "Yes" else "No"}")
                appendLine("📦 Type: <code>$itemType</code>")
                if (price != null && price > 0) {
                    appendLine("💰 Price: <code>$$price $currency</code>")
                }
            }
            
            telegramMessageProvider(chatId, notification)
            println("[${getTimestamp()}] 💳 Sent purchase notification")
        }
    }

    private fun containsLinksOrPaymentSystems(text: String): Boolean {
        val lowerText = text.lowercase()
        
        // Check for links
        val linkPatterns = listOf(
            "http://", "https://", "www.", ".com", ".org", ".net", ".ru", ".uk", ".de", ".fr", ".it", ".es",
            "t.me/", "telegram.me/", "instagram.com/", "facebook.com/", "twitter.com/", "youtube.com/",
            "snapchat.com/", "whatsapp.com/", "discord.gg/", "paypal.me/", "cash.app/", "venmo.com/"
        )
        
        // Check for payment systems
        val paymentPatterns = listOf(
            "paypal", "stripe", "square", "cash app", "venmo", "zelle", "western union", "moneygram",
            "bitcoin", "btc", "ethereum", "eth", "crypto", "cryptocurrency", "wallet", "bank transfer",
            "wire transfer", "ach", "swift", "iban", "card number", "credit card", "debit card",
            "apple pay", "google pay", "samsung pay", "amazon pay", "alipay", "wechat pay"
        )
        
        // Check for links
        val hasLinks = linkPatterns.any { pattern -> lowerText.contains(pattern) }
        
        // Check for payment systems
        val hasPaymentSystems = paymentPatterns.any { pattern -> lowerText.contains(pattern) }
        
        return hasLinks || hasPaymentSystems
    }

    private fun heartBeat(defaultClientWebSocketSession: DefaultClientWebSocketSession) {
        runBlocking {
            println("[${getTimestamp()}] 💓 Starting heartbeat...")
            while (true) {
                try {
                    val heartBeatRequest = WebSocketRequest("get_onlines", "[1]")
                    val heartbeatJson = jsonMapper.writeValueAsString(heartBeatRequest)
                    defaultClientWebSocketSession.send(Frame.Text(heartbeatJson))
                    println("[${getTimestamp()}] 💓 Heartbeat sent: $heartbeatJson")
                    delay(30000) // 30 seconds delay
                } catch (e: Exception) {
                    println("[${getTimestamp()}] ❌ Heartbeat error: ${e.message}")
                    break
                }
            }
        }
    }

    private fun getTimestamp(): String {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    }

    private fun cleanHtmlTags(htmlText: String): String {
        return htmlText
            .replace(Regex("<[^>]*>"), "") // Remove all HTML tags
            .replace("&lt;", "<") // Replace HTML entities
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .trim() // Remove extra spaces
    }
}