import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class AdminBot : TelegramLongPollingBot(System.getenv("TELEGRAM_BOT_TOKEN") ?: throw IllegalArgumentException("TELEGRAM_BOT_TOKEN environment variable is required")) {

    // Manger settings
    private val settingsManager = SettingsManager()
    
    // Database for storing connections by accounts (chatId -> accountName -> connector)
    private val adminOnlyFansConnectorSessions = mutableMapOf<Long, MutableMap<String, OnlyFansConnector>>()
    
    // Temporary storage for information about the last user for each account
    private val lastUserInfo = mutableMapOf<String, AdminBot.UserInfo>() // key: "chatId:accountName"
    
    // Database for storing user responses
    private val userResponses: MutableMap<Long, String> = mutableMapOf()
    private val defaultTriggerWords = listOf("http", "whatsapp", "whatapp", "https", "snapchat")
    private val helpInfoTextFile = File("helpText.txt")

    init {
        // Restore connections for all saved users
        restoreConnections()
    }

    private fun restoreConnections() {
        val savedUsers = settingsManager.getAllUsers()
        if (savedUsers.isNotEmpty()) {
            println("[${getTimestamp()}] 🔄 Restoring connections for ${savedUsers.size} users")
            
            savedUsers.forEach { chatId ->
                val userSettings = settingsManager.getUserSettings(chatId)
                if (userSettings != null) {
                    userSettings.accounts.forEach { (accountName, account) ->
                        try {
                            println("[${getTimestamp()}] 🔌 Restoring connection for account '$accountName' user $chatId")
                            
                            // Create new connection
                            val connector = OnlyFansConnector(
                                chatId, 
                                accountName, 
                                account.token, 
                                this::sendTextMessage, 
                                this::processMessageTriggerWords, 
                                this::updateLastUserInfo
                            )
                            
                            // Save connection
                            if (adminOnlyFansConnectorSessions[chatId] == null) {
                                adminOnlyFansConnectorSessions[chatId] = mutableMapOf()
                            }
                            adminOnlyFansConnectorSessions[chatId]!![accountName] = connector
                            
                            // Start connection in separate thread
                            Thread {
                                try {
                                    connector.start()
                                } catch (e: Exception) {
                                    println("[${getTimestamp()}] ❌ Error restoring connection for $accountName/$chatId: ${e.message}")
                                    sendTextMessage(chatId, "❌ Unable to restore connection for account '$accountName'. Use /token to reconnect.")
                                }
                            }.start()
                            
                        } catch (e: Exception) {
                            println("[${getTimestamp()}] ❌ Error restoring for account '$accountName' user $chatId: ${e.message}")
                        }
                    }
                    
                    if (userSettings.accounts.isNotEmpty()) {
                        sendTextMessage(chatId, "🔄 Connections restored automatically for ${userSettings.accounts.size} accounts!")
                    }
                }
            }
        }
    }

    override fun onUpdateReceived(update: Update) {
        if (update.hasMessage()) {
            val message = update.message
            println("[${getTimestamp()}] 👤 User: ${message.chatId} Message: ${update.message.text}")

            if (message.hasText()) {
                val text = message.text
                val chatId = message.chatId
                println("[${getTimestamp()}] 🔍 Processing command: '$text' for user $chatId")
                
                //adding new admin
                when {
                    text.startsWith("/token") -> {
                        println("[${getTimestamp()}] 🔑 Processing command /token")
                        addNewAdminToken(text, chatId)
                    }
                    text.startsWith("/start") -> {
                        println("[${getTimestamp()}] 🚀 Processing command /start")
                        handleStartCommand(chatId)
                    }
                    text.startsWith("/help") -> {
                        println("[${getTimestamp()}] ❓ Processing command /help")
                        sendHelpMessage(chatId)
                    }
                    text.startsWith("/triggers") -> {
                        println("[${getTimestamp()}] 🎯 Processing command /triggers")
                        handleTriggers(chatId, text)
                    }
                    text.startsWith("/close") -> {
                        println("[${getTimestamp()}] 🔒 Processing command /close")
                        handleCloseCommand(chatId)
                    }
                    text.startsWith("/reconnect") -> {
                        handleReconnectCommand(chatId)
                    }
                    text.startsWith("/allmessages") -> {
                        println("[${getTimestamp()}] 📨 Processing command /allmessages")
                        handleAllMessagesCommand(chatId, text)
                    }
                    text.startsWith("/cleartriggers") -> {
                        println("[${getTimestamp()}] 🧹 Processing command /cleartriggers")
                        handleClearTriggersCommand(chatId)
                    }
                    text.startsWith("/removetrigger") -> {
                        println("[${getTimestamp()}] 🗑️ Processing command /removetrigger")
                        handleRemoveTriggerCommand(chatId, text)
                    }
                    else -> {
                        println("[${getTimestamp()}] 💬 Processing user response")
                        handleUserResponse(chatId, text)
                    }
                }
            }
        }
    }

    private fun handleCloseCommand(chatId: Long) {
        val connectors = adminOnlyFansConnectorSessions[chatId]
        if (connectors != null && connectors.isNotEmpty()) {
            connectors.forEach { (accountName, connector) ->
                connector.close()
            }
            adminOnlyFansConnectorSessions.remove(chatId)
            
            // Remove settings from storage
            settingsManager.removeUserSettings(chatId)
            
            sendTextMessage(chatId, "🔒 All sessions closed and data deleted")
        } else {
            sendTextMessage(chatId, "❌ No active sessions found")
        }
    }

    private fun handleAllMessagesCommand(chatId: Long, msg: String) {
        println("[${getTimestamp()}] 🔧 Processing command /allmessages: '$msg' for user $chatId")
        
        val parts = msg.split(" ")
        when {
            msg == "/allmessages" -> {
                // Show status for all accounts
                val accounts = settingsManager.getUserAccounts(chatId)
                if (accounts.isEmpty()) {
                    sendTextMessage(chatId, "📊 You have no connected accounts")
                } else {
                    val status = accounts.map { (name, account) ->
                        "$name: ${if (account.sendAllMessages) "enabled" else "disabled"}"
                    }.joinToString("\n")
                    sendTextMessage(chatId, "📊 Sending all messages:\n$status")
                }
            }
            parts.size == 3 && parts[2] in listOf("on", "off") -> {
                // Format: /allmessages account_name on/off
                val accountName = parts[1]
                val enable = parts[2] == "on"
                
                if (settingsManager.getUserAccount(chatId, accountName) != null) {
                    settingsManager.updateAccountSendAllMessages(chatId, accountName, enable)
                    val status = if (enable) "enabled" else "disabled"
                    sendTextMessage(chatId, "✅ Sending all messages for account '$accountName' $status")
                } else {
                    sendTextMessage(chatId, "❌ Account '$accountName' not found")
                }
            }
            parts.size == 2 && parts[1] in listOf("on", "off") -> {
                // Format: /allmessages on/off (for all accounts)
                val enable = parts[1] == "on"
                val accounts = settingsManager.getUserAccounts(chatId)
                
                accounts.forEach { (accountName, _) ->
                    settingsManager.updateAccountSendAllMessages(chatId, accountName, enable)
                }
                
                val status = if (enable) "enabled" else "disabled"
                sendTextMessage(chatId, "✅ Sending all messages for all accounts $status")
            }
            else -> {
                sendTextMessage(chatId, "Usage:\n/allmessages - show status\n/allmessages [account_name] on/off - set for specific account\n/allmessages on/off - set for all accounts")
            }
        }
    }

    private fun sendHelpMessage(chatId: Long) {
        val helpText = """
            🤖 <b>OnlyFans Telegram Bot - Help</b>
            
            <b>Main commands:</b>
            /token [account_name] <code>token</code> - Add OnlyFans token and connect
            /close - Close all sessions and delete data
            /reconnect - Forcefully reconnect to all accounts
            /help - Show this help
            
            <b>Message settings:</b>
            /allmessages - Show status for all accounts
            /allmessages [account_name] on/off - Set for specific account
            /allmessages on/off - Set for all accounts
            
            <b>Trigger words:</b>
            /triggers - Show triggers for all accounts
            /triggers words... - Set for all accounts
            /cleartriggers - Delete all user trigger words (leave only base)
            /removetrigger word - Remove one word from user triggers for all accounts
            
            <b>Examples:</b>
            /token main <code>eyJ0eXAi...</code>
            /token backup <code>eyJ0eXAi...</code>
            /allmessages main on
            /triggers http whatsapp snapchat telegram
            /removetrigger telegram
            /cleartriggers
            /reconnect
            
            <b>Support for multiple accounts:</b>
            • Each account can be assigned a unique name
            • If name is not specified, "default" is used
            • Account settings are independent
            • Messages contain account information
            
            <b>Automatic recovery:</b>
            Bot automatically restores connections on restart
            and performs retry attempts on failures.
        """.trimIndent()
        
        sendTextMessage(chatId, helpText)
    }

    private fun addNewAdminToken(text: String, chatId: Long) {
        try {
            val parts = text.removePrefix("/token ").split(" ", limit = 2)
            val accountName: String
            val token: String
            
            if (parts.size == 2) {
                // Format: /token account_name token
                accountName = parts[0]
                token = parts[1]
            } else {
                // Format: /token token (use default as name)
                accountName = "default"
                token = parts[0]
            }
            
            if (token.isEmpty()) {
                throw Exception("Token not found")
            }
            
            // Save account settings
            settingsManager.saveUserAccount(chatId, accountName, token)
            
            // Close old connection for this account, if exists
            adminOnlyFansConnectorSessions[chatId]?.get(accountName)?.close()
            
            // Create new connection
            val onlyFansConnector = OnlyFansConnector(
                chatId, 
                accountName, 
                token, 
                this::sendTextMessage, 
                this::processMessageTriggerWords, 
                this::updateLastUserInfo
            )
            
            // Save connection
            if (adminOnlyFansConnectorSessions[chatId] == null) {
                adminOnlyFansConnectorSessions[chatId] = mutableMapOf()
            }
            adminOnlyFansConnectorSessions[chatId]!![accountName] = onlyFansConnector
            
            // Start connection in separate thread
            Thread {
                try {
                    onlyFansConnector.start()
                } catch (e: Exception) {
                    println("[${getTimestamp()}] ❌ Error starting connection: ${e.message}")
                    sendTextMessage(chatId, "❌ Connection error for account '$accountName': ${e.message}")
                }
            }.start()
            
            sendTextMessage(chatId, "✅ OnlyFans token for account '$accountName' successfully added and connection established")
        } catch (e: Exception) {
            sendTextMessage(chatId, "❌ Error adding token: ${e.message}\n\nUsage: /token [account_name] <code>token</code>")
        }
    }

    fun handleTriggers(chatId: Long, msg: String) {
        when {
            msg == "/triggers" -> {
                // Show triggers for all accounts
                val accounts = settingsManager.getUserAccounts(chatId)
                if (accounts.isEmpty()) {
                    sendTextMessage(chatId, "You have no connected accounts")
                } else {
                    val triggersInfo = accounts.map { (name, account) ->
                        val userTriggers = account.triggerWords.filter { it !in defaultTriggerWords }
                        val result = StringBuilder("$name:\n")
                        result.append("  📌 Base: ${defaultTriggerWords.joinToString(", ")}\n")
                        if (userTriggers.isNotEmpty()) {
                            result.append("  ➕ Added: ${userTriggers.joinToString(", ")}")
                        } else {
                            result.append("  ➕ Added: none")
                        }
                        result.toString()
                    }.joinToString("\n\n")
                    sendTextMessage(chatId, "Current triggers:\n\n$triggersInfo")
                }
            }
            msg.startsWith("/triggers ") -> {
                // Format: /triggers words... (for all accounts)
                val newWords = msg.removePrefix("/triggers ").lowercase().split(" ", ",", ";").filter { it.isNotEmpty() }
                val accounts = settingsManager.getUserAccounts(chatId)
                
                if (accounts.isEmpty()) {
                    sendTextMessage(chatId, "You have no connected accounts")
                } else {
                    accounts.forEach { (accountName, account) ->
                        // Get current user words (not base)
                        val currentUserWords = account.triggerWords.filter { it !in defaultTriggerWords }
                        // Combine with new, remove duplicates
                        val updatedUserWords = (currentUserWords + newWords).toSet().toList()
                        // Final list: base + user words
                        val updatedTriggers = defaultTriggerWords + updatedUserWords
                        settingsManager.updateAccountTriggerWords(chatId, accountName, updatedTriggers)
                    }
                    // Get actual accounts after update
                    val updatedAccounts = settingsManager.getUserAccounts(chatId)
                    sendTextMessage(chatId, "✅ New user trigger words added for all accounts:\n📌 Base: ${defaultTriggerWords.joinToString(", ")}\n➕ All user: " +
                        updatedAccounts.values.flatMap { it.triggerWords.filter { w -> w !in defaultTriggerWords } }.joinToString(", "))
                }
            }
            else -> {
                sendTextMessage(chatId, "Usage:\n/triggers - show triggers\n/triggers words... - set for all accounts")
            }
        }
    }

    private fun handleStartCommand(chatId: Long) {
        // Handle /start command from user
        sendTextMessage(
            chatId,
            "Welcome! Please authenticate via personal token. Command: /token [WS token] or command /help for information"
        )
    }

    private fun handleUserResponse(chatId: Long, response: String) {
        // Handle user response
        userResponses[chatId] = response
        sendTextMessage(chatId, "For information, type command /help")
    }

    fun processMessageTriggerWords(chatId: Long, messageText: String, accountName: String) {
        println("[${getTimestamp()}] 🔍 Processing OnlyFans message for account '$accountName' user $chatId")
        println("[${getTimestamp()}] 📝 Message text: $messageText")
        
        val account = settingsManager.getUserAccount(chatId, accountName)
        if (account == null) {
            println("[${getTimestamp()}] ❌ Account '$accountName' not found in settings")
            return
        }
        
        val userInfo = getLastUserInfo(chatId, accountName)
        if (userInfo?.canEarn == true) {
            println("[${getTimestamp()}] 🚫 Message from model (canEarn == true) — not sent to Telegram")
            return
        }
        
        if (account.sendAllMessages) {
            println("[${getTimestamp()}] 📨 Sending all messages to user $chatId")
            val formattedMessage = buildString {
                appendLine("💬 <b>NEW MESSAGE</b>")
                appendLine("🏷️ Account: <code>$accountName</code>")
                if (userInfo != null) {
                    appendLine(" From: <code>${userInfo.name}</code> (@${userInfo.username})")
                    appendLine("🆔 ID: <code>${userInfo.id}</code>")
                    appendLine("💰 Subscription: <code>$${userInfo.subscribePrice}</code>")
                    appendLine("💸 Can earn: ${if (userInfo.canEarn) "Yes" else "No"}")
                }
                appendLine("\n📝 <b>Message text:</b>")
                appendLine("<code>$messageText</code>")
            }
            sendTextMessage(chatId, formattedMessage)
        } else {
            println("[${getTimestamp()}] 🔍 DEBUG: Checking trigger words for text: '$messageText'")
            println("[${getTimestamp()}] 🔍 DEBUG: Account trigger words: ${account.triggerWords}")
            
            val hasTrigger = account.triggerWords.any { trigger -> 
                val contains = messageText.lowercase().contains(trigger.lowercase())
                if (contains) {
                    println("[${getTimestamp()}] 🔍 DEBUG: Trigger word '$trigger' matched!")
                }
                contains
            }
            
            if (hasTrigger) {
                println("[${getTimestamp()}] 🎯 Trigger word found in message")
                val matchedTriggers = account.triggerWords.filter { messageText.lowercase().contains(it.lowercase()) }
                println("[${getTimestamp()}] 🔍 DEBUG: Matched triggers: ${matchedTriggers.joinToString(", ")}")
                
                val formattedMessage = buildString {
                    appendLine("🎯 <b>MESSAGE WITH TRIGGER</b>")
                    appendLine("\uD83C\uDF10 Dialog with account: <code>$accountName</code>")
                    if (userInfo != null) {
                        appendLine("👤 Sender: <code>${userInfo.name}</code> (@${userInfo.username})")
                        appendLine("💰 Subscription: <code>$${userInfo.subscribePrice}</code>")
                        appendLine("✅ Verification: ${if (userInfo.isVerified) "Yes" else "No"}")
                    } else {
                        appendLine("👤 Sender: unknown")
                    }
                    appendLine("\n🔍 Trigger words: <code>${matchedTriggers.joinToString(", ")}</code>")
                    appendLine("\n📝 <b>Message text:</b>")
                    appendLine("<code>$messageText</code>")
                }
                sendTextMessage(chatId, formattedMessage)
            }
        }
    }

    data class UserInfo(
        val id: Long,
        val name: String,
        val username: String,
        val isVerified: Boolean,
        val subscribePrice: Int,
        val lastSeen: String,
        val canEarn: Boolean
    )
    
    fun updateLastUserInfo(chatId: Long, accountName: String, userInfo: UserInfo) {
        lastUserInfo["$chatId:$accountName"] = userInfo
    }
    
    private fun getLastUserInfo(chatId: Long, accountName: String): UserInfo? {
        return lastUserInfo["$chatId:$accountName"]
    }

    fun sendTextMessage(chatId: Long, text: String, accountName: String = "") {
        val message = SendMessage()
        message.chatId = chatId.toString()
        message.text = text
        message.parseMode = "HTML"
        
        try {
            execute(message)
        } catch (e: TelegramApiException) {
            e.printStackTrace()
        }
    }

    override fun getBotUsername(): String {
        return "OnlyFansMonitorBot"
    }
    
    private fun getTimestamp(): String {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    }

    fun seedAdmin() {
        // Test function (removed in production version)
        // Use command /token to add tokens
    }

    private fun handleReconnectCommand(chatId: Long) {
        val connectors = adminOnlyFansConnectorSessions[chatId]
        if (connectors != null && connectors.isNotEmpty()) {
            sendTextMessage(chatId, "🔄 Reconnecting...")
            Thread {
                try {
                    connectors.forEach { (accountName, connector) ->
                        connector.reconnect()
                    }
                } catch (e: Exception) {
                    println("[${getTimestamp()}] ❌ Reconnection error: ${e.message}")
                    sendTextMessage(chatId, "❌ Reconnection error: ${e.message}")
                }
            }.start()
        } else {
            sendTextMessage(chatId, "❌ No active connections. Use /token to connect.")
        }
    }

    private fun handleClearTriggersCommand(chatId: Long) {
        val accounts = settingsManager.getUserAccounts(chatId)
        if (accounts.isEmpty()) {
            sendTextMessage(chatId, "You have no connected accounts")
        } else {
            accounts.forEach { (accountName, _) ->
                settingsManager.updateAccountTriggerWords(chatId, accountName, defaultTriggerWords)
            }
            sendTextMessage(chatId, "🧹 All user trigger words deleted. Only base left: ${defaultTriggerWords.joinToString(", ")}")
        }
    }

    private fun handleRemoveTriggerCommand(chatId: Long, msg: String) {
        val wordToRemove = msg.removePrefix("/removetrigger ").trim().lowercase()
        if (wordToRemove.isEmpty()) {
            sendTextMessage(chatId, "Specify word to remove. Example: /removetrigger telegram")
            return
        }
        val accounts = settingsManager.getUserAccounts(chatId)
        if (accounts.isEmpty()) {
            sendTextMessage(chatId, "You have no connected accounts")
        } else {
            var removed = false
            accounts.forEach { (accountName, account) ->
                val userWords = account.triggerWords.filter { it !in defaultTriggerWords }
                if (wordToRemove in userWords) {
                    val updatedUserWords = userWords.filter { it != wordToRemove }
                    val updatedTriggers = defaultTriggerWords + updatedUserWords
                    settingsManager.updateAccountTriggerWords(chatId, accountName, updatedTriggers)
                    removed = true
                }
            }
            if (removed) {
                sendTextMessage(chatId, "🗑️ Word '$wordToRemove' removed from user triggers for all accounts.")
            } else {
                sendTextMessage(chatId, "Word '$wordToRemove' not found among user triggers.")
            }
        }
    }
}