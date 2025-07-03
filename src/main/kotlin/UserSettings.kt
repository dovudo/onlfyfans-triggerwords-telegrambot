import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File

data class OnlyFansAccount(
    val accountName: String,
    val token: String,
    val triggerWords: List<String> = listOf("http", "whatsapp", "whatapp", "https", "://", "snapchat"),
    val sendAllMessages: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis()
)

data class UserSettings(
    val chatId: Long,
    val accounts: Map<String, OnlyFansAccount> = emptyMap(),
    val lastUpdated: Long = System.currentTimeMillis()
)

class SettingsManager {
    private val settingsFile = File("user_settings.json")
    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
    private val userSettings = mutableMapOf<Long, UserSettings>()

    init {
        loadSettings()
    }

    fun saveUserAccount(chatId: Long, accountName: String, token: String, 
                       triggerWords: List<String> = emptyList(), sendAllMessages: Boolean = false) {
        val currentSettings = userSettings[chatId] ?: UserSettings(chatId)
        val account = OnlyFansAccount(
            accountName = accountName,
            token = token,
            triggerWords = if (triggerWords.isNotEmpty()) triggerWords else listOf("http", "whatsapp", "whatapp", "https", "://", "snapchat"),
            sendAllMessages = sendAllMessages
        )
        
        val updatedAccounts = currentSettings.accounts.toMutableMap()
        updatedAccounts[accountName] = account
        
        userSettings[chatId] = currentSettings.copy(accounts = updatedAccounts)
        saveToFile()
        println("[SettingsManager] 💾 Account '$accountName' for user $chatId saved")
    }

    fun getUserSettings(chatId: Long): UserSettings? {
        return userSettings[chatId]
    }

    fun getUserAccount(chatId: Long, accountName: String): OnlyFansAccount? {
        return userSettings[chatId]?.accounts?.get(accountName)
    }

    fun getUserAccounts(chatId: Long): Map<String, OnlyFansAccount> {
        return userSettings[chatId]?.accounts ?: emptyMap()
    }

    fun updateAccountTriggerWords(chatId: Long, accountName: String, triggerWords: List<String>) {
        userSettings[chatId]?.let { settings ->
            val account = settings.accounts[accountName]
            if (account != null) {
                val updatedAccount = account.copy(triggerWords = triggerWords)
                val updatedAccounts = settings.accounts.toMutableMap()
                updatedAccounts[accountName] = updatedAccount
                userSettings[chatId] = settings.copy(accounts = updatedAccounts)
                saveToFile()
                println("[SettingsManager] 🎯 Trigger words for account '$accountName' for user $chatId updated")
            }
        }
    }

    fun updateAccountSendAllMessages(chatId: Long, accountName: String, sendAllMessages: Boolean) {
        userSettings[chatId]?.let { settings ->
            val account = settings.accounts[accountName]
            if (account != null) {
                val updatedAccount = account.copy(sendAllMessages = sendAllMessages)
                val updatedAccounts = settings.accounts.toMutableMap()
                updatedAccounts[accountName] = updatedAccount
                userSettings[chatId] = settings.copy(accounts = updatedAccounts)
                saveToFile()
                println("[SettingsManager] 📨 Send all messages mode for account '$accountName' for user $chatId updated")
            }
        }
    }

    fun removeUserAccount(chatId: Long, accountName: String) {
        userSettings[chatId]?.let { settings ->
            val updatedAccounts = settings.accounts.toMutableMap()
            updatedAccounts.remove(accountName)
            if (updatedAccounts.isEmpty()) {
                userSettings.remove(chatId)
            } else {
                userSettings[chatId] = settings.copy(accounts = updatedAccounts)
            }
            saveToFile()
            println("[SettingsManager] 🗑️ Account '$accountName' for user $chatId removed")
        }
    }

    fun removeUserSettings(chatId: Long) {
        userSettings.remove(chatId)
        saveToFile()
        println("[SettingsManager] 🗑️ All settings for user $chatId removed")
    }

    fun getAllUsers(): List<Long> {
        return userSettings.keys.toList()
    }

    private fun loadSettings() {
        try {
            if (settingsFile.exists()) {
                val jsonContent = settingsFile.readText()
                if (jsonContent.isNotEmpty()) {
                    val settingsList: List<UserSettings> = mapper.readValue(jsonContent)
                    userSettings.clear()
                    settingsList.forEach { settings ->
                        userSettings[settings.chatId] = settings
                    }
                    val totalAccounts = userSettings.values.sumOf { it.accounts.size }
                    println("[SettingsManager] 📂 Loaded settings for ${userSettings.size} users ($totalAccounts accounts)")
                }
            }
        } catch (e: Exception) {
            println("[SettingsManager] ❌ Error loading settings: ${e.message}")
        }
    }

    private fun saveToFile() {
        try {
            val settingsList = userSettings.values.toList()
            val jsonContent = mapper.writeValueAsString(settingsList)
            settingsFile.writeText(jsonContent)
            println("[SettingsManager] 💾 Settings saved to file")
        } catch (e: Exception) {
            println("[SettingsManager] ❌ Error saving settings: ${e.message}")
        }
    }
} 