import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession

fun main(args: Array<String>) {
    println("Starting OnlyFans Telegram Bot...")
    println("Program arguments: ${args.joinToString()}")

    try {
        val botsApi = TelegramBotsApi(DefaultBotSession::class.java)
        val bot = AdminBot()
        botsApi.registerBot(bot)
        println("Bot started successfully!")
        
        // Keep the application running
        while (true) {
            Thread.sleep(1000)
        }
    } catch (e: Exception) {
        println("Error starting bot: ${e.message}")
        e.printStackTrace()
    }
}

