
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession



    fun main(args: Array<String>) {
        println("Starting the bot ...")
        println("Program arguments: ${args.joinToString()}")
        val botsApi: TelegramBotsApi = TelegramBotsApi(DefaultBotSession::class.java)
        val bot = AdminBot()
        botsApi.registerBot(bot)
        //bot.seedAdmin()
    }

