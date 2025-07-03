# OnlyFans Telegram Bot

A Telegram bot for monitoring and notifications about messages from OnlyFans. The bot connects to the OnlyFans WebSocket API and sends notifications to Telegram when messages with trigger words are received.

## 🔥 Main Features

- **Real-time monitoring** of messages from OnlyFans
- **Trigger words** for automatic notification about important messages
- **Multiple OnlyFans accounts** support for a single Telegram user
- **Notification settings** – you can enable sending all messages or only those with trigger words
- **Automatic reconnection** in case of connection failures
- **Persistent user settings storage**

## 📋 Bot Commands

### Main Commands
- `/start` – Start working with the bot
- `/help` – Show help with all commands
- `/token [account_name] <token>` – Add OnlyFans token and connect account
- `/close` – Close all sessions and delete your data
- `/reconnect` – Force reconnect to all accounts

### Message Settings
- `/allmessages` – Show status for all accounts
- `/allmessages [account_name] on/off` – Set for a specific account
- `/allmessages on/off` – Set for all accounts

### Trigger Words
- `/triggers` – Show current trigger words
- `/triggers word1 word2 ...` – Add new trigger words (they will be added to the existing ones)
- `/cleartriggers` – Remove all custom trigger words (reset to default)
- `/removetrigger word` – Remove a specific word from your trigger list

## ⚙️ Setup & Launch

1. **Clone the repository:**
   ```sh
   git clone https://github.com/yourusername/onlyfans-telegram-bot.git
   cd onlyfans-telegram-bot
   ```
2. **Configure environment variables:**
   - `TELEGRAM_BOT_TOKEN` – your Telegram bot token (get it from @BotFather)
   - (Optionally) configure OnlyFans tokens via bot commands

3. **Build and run:**
   ```sh
   ./gradlew build
   ./gradlew run
   ```
   Or use Docker:
   ```sh
   docker build -t onlyfans-telegram-bot .
   docker run -e TELEGRAM_BOT_TOKEN=your_token onlyfans-telegram-bot
   ```

## 🛡️ Security & Privacy
- The bot does **not** store your OnlyFans tokens or messages anywhere except in your local storage (user_settings.json).
- All connections are made directly from your server to OnlyFans and Telegram.
- Do **not** share your tokens with anyone.

## 📝 Notes
- OnlyFans does **not** provide a public OAuth API, so you need to get your WebSocket token manually (see instructions in the bot or in the Wiki).
- The bot is for personal use and educational purposes only.

## 🤝 Contributing
Pull requests and suggestions are welcome! Please open an issue or submit a PR.

## 📄 License
MIT License
