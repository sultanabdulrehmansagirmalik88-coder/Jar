# Jarvis1

Jarvis1 is an Android assistant app built with Kotlin, Java 17, and Gradle. Set your Groq (or xAI) API key in Settings; keys are stored with AndroidX EncryptedSharedPreferences. Enable Screen Control from Settings to use accessibility gestures and the floating command bubble.

## Version 2.0

Version 2 adds searchable chat history, pinned and renameable chats, individual chat deletion, full-history export, answer regeneration and cancellation, configurable quick prompts and reply length, searchable notes with reminders, custom command shortcuts, recent action history with volume undo, bubble visibility and position settings, speech speed controls, Screen Control troubleshooting, and a turn-by-turn Live voice session using the configured Groq/xAI provider. Existing message copy/share, chat export, notes, and accessibility status remain available.

Build with Android SDK 34 and Java 17 using the Gradle 8.4 wrapper:

```sh
./gradlew :app:assembleDebug :app:bundleRelease --no-daemon
```
