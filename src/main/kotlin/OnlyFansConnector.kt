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

    // Unknown event alert deduplication (per event type)
    private val lastUnknownAlertAtByType = mutableMapOf<String, Long>()
    private val unknownEventAlertCooldownMs = 30 * 60 * 1000L // 30 minutes
    private val enableUnknownEventAlerts: Boolean =
        (System.getenv("OF_ALERT_UNKNOWN_EVENTS") ?: "false").lowercase() in listOf("1", "true", "yes", "on")

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
                    // Determine top-level keys to identify event type
                    val keys = jsonNode.fieldNames().asSequence().toList()
                    val eventType = keys.firstOrNull() ?: "unknown"
                    println("[${getTimestamp()}] ❓ Unknown message type: $eventType; keys=$keys")

                    if (enableUnknownEventAlerts) {
                        // Rate-limit alerts per event type
                        val now = System.currentTimeMillis()
                        val lastSent = lastUnknownAlertAtByType[eventType] ?: 0L
                        val shouldAlert = now - lastSent >= unknownEventAlertCooldownMs

                        if (shouldAlert) {
                            lastUnknownAlertAtByType[eventType] = now
                            val shortPayload = cleanHtmlTags(message).take(800)
                            val alert = buildString {
                                appendLine("⚠️ <b>Unknown WebSocket event</b>")
                                appendLine("🏷️ Account: <code>$accountName</code>")
                                appendLine("🧩 Type: <code>$eventType</code>")
                                if (keys.isNotEmpty()) {
                                    appendLine("🔑 Keys: <code>${keys.joinToString(", ")}</code>")
                                }
                                appendLine("\n📝 Payload (truncated):")
                                appendLine("<code>$shortPayload</code>")
                                appendLine("\nNote: This may indicate a new event (e.g., token update).")
                            }
                            telegramMessageProvider(chatId, alert)
                        } else {
                            println("[${getTimestamp()}] ⏱️ Unknown event '$eventType' alert suppressed due to cooldown")
                        }
                    } else {
                        println("[${getTimestamp()}] 🚫 Unknown event alerts disabled by configuration")
                    }
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
            
            // Determine message direction and handle only incoming (with fromUser)
            val fromUser = messageNode.findValue("fromUser")
            val toUser = messageNode.findValue("toUser")

            if (fromUser != null) {
                // Save user info for the actual sender
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

                // Check for links or payment systems (now returns triggers)
                val triggers = containsLinksOrPaymentSystems(cleanText)
                if (triggers.isNotEmpty()) {
                    val canEarn = fromUser.findValue("canEarn")?.asBoolean() ?: false
                    if (!canEarn) {
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
                            appendLine("<b>Triggers:</b> <code>${triggers.joinToString(", ")}</code>")
                            appendLine("\n📝 <b>Message text:</b>")
                            appendLine("<code>$cleanText</code>")
                        }
                        telegramMessageProvider(chatId, notification)
                        println("[${getTimestamp()}] 🚨 Sent important message notification (triggers: ${triggers.joinToString(", ")})")
                    } else {
                        println("[${getTimestamp()}] 🚫 Message from model (canEarn == true) — alert not sent")
                    }
                }

                // Check trigger words for regular messages (incoming only)
                processMessageTriggerWords(chatId, cleanText, accountName)
            } else if (toUser != null) {
                // Outgoing message (toUser) — ignore to prevent stale sender mapping
                val toUserName = toUser.findValue("name")?.asText()
                val toUserId = toUser.findValue("id")?.asLong()
                println("[${getTimestamp()}] ⤴️ Outgoing message to $toUserName (id=$toUserId) — ignored for notifications")
            } else {
                // Neither fromUser nor toUser present
                println("[${getTimestamp()}] ⚠️ Message without fromUser/toUser — skipped")
            }
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

    private fun containsLinksOrPaymentSystems(text: String): List<String> {
        val lowerText = text.lowercase()
        val triggers = mutableListOf<String>()
        
        println("[${getTimestamp()}] 🔍 DEBUG: Checking text: '$text'")
        println("[${getTimestamp()}] 🔍 DEBUG: Lowercase: '$lowerText'")

        // Check for links (substring match)
        val linkPatterns = listOf(
            "://", "https", "www.", ".com", ".org", ".net", ".ru", ".uk", ".de", ".fr", ".it", ".es",
            "t.me/", "telegram.me/", "instagram.com/", "facebook.com/", "twitter.com/", "youtube.com/",
            "snapchat.com/", "whatsapp.com/", "discord.gg/", "paypal.me/", "cash.app/", "venmo.com/"
        )
        for (pattern in linkPatterns) {
            if (lowerText.contains(pattern)) {
                triggers.add(pattern)
                println("[${getTimestamp()}] 🔍 DEBUG: Link pattern '$pattern' matched!")
            }
        }

        // Check for payment systems (word boundary match)
        val paymentPatterns = listOf(
            "paypal", "stripe", "square", "cash app", "venmo", "zelle", "western union", "moneygram",
            "bitcoin", "btc", "ethereum", "crypto", "cryptocurrency", "wallet", "bank transfer",
            "wire transfer", "swift", "iban", "card number", "credit card", "debit card",
            "apple pay", "google pay", "samsung pay", "amazon pay", "alipay", "wechat pay"
        )
        for (pattern in paymentPatterns) {
            // Для составных паттернов ищем как фразу, для одиночных — по word boundary
            val regex = if (pattern.contains(" ")) {
                Regex("\\b" + Regex.escape(pattern) + "\\b", RegexOption.IGNORE_CASE)
            } else {
                Regex("\\b" + Regex.escape(pattern) + "\\b", RegexOption.IGNORE_CASE)
            }
            if (regex.containsMatchIn(lowerText)) {
                triggers.add(pattern)
                println("[${getTimestamp()}] 🔍 DEBUG: Payment pattern '$pattern' matched!")
            }
        }
        
        println("[${getTimestamp()}] 🔍 DEBUG: Final triggers: ${triggers.joinToString(", ")}")
        return triggers
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

/*
    {
	"api2_chat_message": {
		"responseType": "message",
		"text": "<p>How I’m new on this .. trying to get some tips on how it works.. <\/p>",
		"giphyId": null,
		"lockedText": false,
		"isFree": true,
		"price": 0,
		"isMediaReady": true,
		"mediaCount": 1,
		"media": [
			{
				"id": 3950387999,
				"type": "photo",
				"convertedToVideo": false,
				"canView": true,
				"hasError": false,
				"createdAt": null,
				"isReady": true,
				"files": {
					"full": {
						"url": "https:\/\/cdn2.onlyfans.com\/files\/8\/8f\/8fa1803b42ad65a162694ac21a38247c\/1170x1243_49d028cc592e3bb14fa60b24424f9a0a.jpg?Tag=2&u=0&Expires=1753351200&Signature=OKFmiQEXYU-ABGpOjgVqFENntY-SZyQEiY~u9oECZnmQ1rAh76EXayT4b3Ehpgem2dCDjH~68gigYi5fXlATdNlzCJafsnGgK0T9XU7DGL6qcRgPQvcfsDBJGtW03fbsEyJleKxmlNR3Arrwctv6dzuB2zO2qfGUD9pvj76-sRE0rggvL2H80TGwd3WcqInKMnHD5BbI9n11YaknE6RxVbfjFztX-LwpgWoMtYWp3k16LjWAIxGTuvU~gSp~1JsVnr-w4TUAxvW5KvOd2bubziu8KNTFtMfupK-q4tBGTiaG-T~6xyoyW112DpV8hmHQ5tEqR4lui9CwKO3qw~N5fg__&Key-Pair-Id=APKAUSX4CWPPATFK2DGD",
						"width": 1170,
						"height": 1243,
						"size": 0,
						"sources": []
					},
					"thumb": {
						"url": "https:\/\/cdn2.onlyfans.com\/files\/7\/74\/740ff4b7436f61fd11b4da6ec5d0ea8e\/300x300_49d028cc592e3bb14fa60b24424f9a0a.jpg?Tag=2&u=0&Expires=1753351200&Signature=GSPYLyJY~CqKWFC0erLx~4lqS6luEWASrBs-aTUM6tWq13vjefpQh-pj7VrPjkTMGrXlhPV3AZ6oQ2-huMCiM9t2WHygCinWHOydq2JdmW9M7TVvFwtHGhLqdtNSADlCxM5bQpdSGWtyXh0hLzPrZX~zFf4MumolWAYx9vakuZwJAM8tudqE0x6nTUKGVGpv3~4oi0ig3DtLJs07JP3t2fhLH6mPaUp-OKW0ubC0mtq1Eg8OgP2Wz6aDnySNovr3u0OpMQljFrXRKhEAKjn6zpRqG6L0OUR5KohcJK2XkrX4Hia1a~B80wqrFK0ejayaIawEGQ3bOL4HWxbsD5PJVQ__&Key-Pair-Id=APKAUSX4CWPPATFK2DGD",
						"width": 300,
						"height": 300,
						"size": 0
					},
					"preview": {
						"url": "https:\/\/cdn2.onlyfans.com\/files\/b\/b2\/b2d7ab97d9ff9f5347b2a302ff990f98\/960x1020_49d028cc592e3bb14fa60b24424f9a0a.jpg?Tag=2&u=0&Expires=1753351200&Signature=afHpGZQ0SBYEmPa41e9SFtN2OQShvfrooJpSlZwe9A2UQ9EM2h-tpHV4N~yrVl7SM4zQdWFHItuYMBwhxjx3GiY4kG2XBQ5jmn6Vk1Hxj~slHEgAVLWtYZVHjfYklDruJRhPCT8Q9G6dLxTV0MDh4NeZ2DiFWFtg7MAeIKpQ6K8oSMwbf8m2YLoYc1bx53ezEUIypLsX8D9FuSX7XemhVX2IJF0O4OFD-blYxXnAnICoXPHlrLwsL0xBTIgxSKMPT7t~7ZRVcDmV-Pgoe5uZj8EAKIl0AoJy21wA-aygUNNAiDxhomCWsT7rgLH9xArGGMp8rXirhqOhxAu6T1p9mA__&Key-Pair-Id=APKAUSX4CWPPATFK2DGD",
						"width": 960,
						"height": 1020,
						"size": 0
					},
					"squarePreview": {
						"url": "https:\/\/cdn2.onlyfans.com\/files\/e\/ea\/eaf61485d01896ca162fc35071543424\/960x960_49d028cc592e3bb14fa60b24424f9a0a.jpg?Tag=2&u=0&Expires=1753351200&Signature=gffOiLi1O1kkXcHmO6asBzBjknYeyc2r0p383bRlB6EA76zPrYvEKnQ8rzU5XBGw3rN1-APTdUFYM7Y0WlHXpou4JBcDSC6VxNLbWPlO6hIV4ZMdfQ1n-ArgGZfYM69j7uKTkIfAdSkdM7Eq1CaQkueeBLle3wQqOjmrNOJ~JnSzDD4VP12qNct88L94yq9Lb85-q8dZNNgDMBbfuttmTHB7bcSKxUPWRNEi-MgF7btgMDzLLcsKtvv9j3Pr1484I4MXIJSFCH8UdgmgecHqkOTAhBy2JKirVMegPc0gSZMRmzadG6gH-H4mMDTpdUjoEBnnM6EFMZCBZYfcGXtvWA__&Key-Pair-Id=APKAUSX4CWPPATFK2DGD",
						"width": 960,
						"height": 960,
						"size": 0
					}
				},
				"duration": 0,
				"hasCustomPreview": false,
				"videoSources": {
					"720": null,
					"240": null
				}
			}
		],
		"previews": [],
		"isTip": false,
		"isReportedByMe": false,
		"isCouplePeopleMedia": false,
		"queueId": 26439068590,
		"isMarkdownDisabled": true,
		"fromUser": {
			"view": "s",
			"avatar": "https:\/\/public.onlyfans.com\/files\/c\/cn\/cnc\/cncrvunvotej7r4q7gobjvfn26gchiyj1753264394\/104080948\/avatar.jpg",
			"avatarThumbs": {
				"c50": "https:\/\/thumbs.onlyfans.com\/public\/files\/thumbs\/c50\/c\/cn\/cnc\/cncrvunvotej7r4q7gobjvfn26gchiyj1753264394\/104080948\/avatar.jpg",
				"c144": "https:\/\/thumbs.onlyfans.com\/public\/files\/thumbs\/c144\/c\/cn\/cnc\/cncrvunvotej7r4q7gobjvfn26gchiyj1753264394\/104080948\/avatar.jpg"
			},
			"header": "https:\/\/public.onlyfans.com\/files\/i\/iw\/iw8\/iw82dbz98uzio4z7cae0k3a72dpll6eq1702634451\/104080948\/header.jpg",
			"headerSize": {
				"width": 1938,
				"height": 1938
			},
			"headerThumbs": {
				"w480": "https:\/\/thumbs.onlyfans.com\/public\/files\/thumbs\/w480\/i\/iw\/iw8\/iw82dbz98uzio4z7cae0k3a72dpll6eq1702634451\/104080948\/header.jpg",
				"w760": "https:\/\/thumbs.onlyfans.com\/public\/files\/thumbs\/w760\/i\/iw\/iw8\/iw82dbz98uzio4z7cae0k3a72dpll6eq1702634451\/104080948\/header.jpg"
			},
			"id": 104080948,
			"name": "ArmandoCharicata",
			"username": "armandocharicata",
			"canLookStory": true,
			"canCommentStory": true,
			"hasNotViewedStory": false,
			"isVerified": true,
			"canPayInternal": true,
			"hasScheduledStream": false,
			"hasStream": false,
			"hasStories": false,
			"tipsEnabled": false,
			"tipsTextEnabled": true,
			"tipsMin": 5,
			"tipsMinInternal": 1,
			"tipsMax": 200,
			"canEarn": true,
			"canAddSubscriber": true,
			"subscribePrice": 0,
			"displayName": "",
			"notice": "",
			"isPaywallRequired": true,
			"isRestricted": false,
			"canRestrict": true,
			"subscribedBy": true,
			"subscribedByExpire": false,
			"subscribedByExpireDate": "2025-07-30T09:23:27+00:00",
			"subscribedByAutoprolong": true,
			"subscribedIsExpiredNow": false,
			"currentSubscribePrice": 0,
			"subscribedOn": null,
			"subscribedOnExpiredNow": true,
			"subscribedOnDuration": "5 months",
			"listsStates": [
				{
					"id": "fans",
					"type": "fans",
					"name": "Fans",
					"hasUser": false,
					"canAddUser": false,
					"cannotAddUserReason": null
				},
				{
					"id": "following",
					"type": "following",
					"name": "Following",
					"hasUser": true,
					"canAddUser": false,
					"cannotAddUserReason": "ALREADY_EXISTS"
				},
				{
					"id": "rebill_off",
					"type": "rebill_off",
					"name": "Renew Off",
					"hasUser": false,
					"canAddUser": false,
					"cannotAddUserReason": null
				},
				{
					"id": "friends",
					"type": "friends",
					"name": "Friends",
					"hasUser": false,
					"canAddUser": true,
					"cannotAddUserReason": null
				},
				{
					"id": "muted",
					"type": "muted",
					"name": "Muted",
					"hasUser": false,
					"canAddUser": true,
					"cannotAddUserReason": null
				},
				{
					"id": "rebill_on",
					"type": "rebill_on",
					"name": "Renew On",
					"hasUser": false,
					"canAddUser": false,
					"cannotAddUserReason": null
				},
				{
					"id": "recent",
					"type": "recent",
					"name": "Recent (last 24 hours)",
					"hasUser": false,
					"canAddUser": false,
					"cannotAddUserReason": "SYSTEM_LIST"
				},
				{
					"id": "tagged",
					"type": "tagged",
					"name": "Tagged",
					"hasUser": false,
					"canAddUser": false,
					"cannotAddUserReason": "SYSTEM_LIST"
				}
			],
			"showMediaCount": true,
			"lastSeen": "2025-07-23T09:58:10+00:00",
			"canReport": true
		},
		"isFromQueue": true,
		"canUnsendQueue": true,
		"unsendSecondsQueue": 1000000,
		"id": 6432457811894,
		"isOpened": false,
		"isNew": true,
		"createdAt": "2025-07-23T10:01:22+00:00",
		"changedAt": "2025-07-23T10:01:22+00:00",
		"cancelSeconds": 86397,
		"isLiked": false,
		"canPurchase": false,
		"canPurchaseReason": "free",
		"canReport": true,
		"canBePinned": true,
		"isPinned": false
	}
}
*/