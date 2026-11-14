# Pancar

An Android app for live streaming gameplay to YouTube, Facebook, Twitch and other RTMP servers.

## Features

- One-tap YouTube streaming with Google sign-in
- Multistreaming to several destinations at once
- Screen and game audio capture with optional microphone
- Floating overlay with live time, viewers, chat and controls
- Privacy screen
- 720p or 1080p at 30 or 60 fps, with adaptive bitrate
- Automatic reconnect
- RTMPS support

## Requirements

- Android 10 or later

## Building

```bash
./gradlew assembleDebug
```

Requires JDK 17 and Android SDK 37.

YouTube sign-in requires an Android OAuth client in Google Cloud Console, set up with the app's package name and signing certificate SHA-1, and with the YouTube Data API v3 enabled.

## Built with

- [Kotlin](https://kotlinlang.org/) and [Jetpack Compose](https://developer.android.com/compose)
- [RootEncoder](https://github.com/pedroSG94/RootEncoder)
- [Google Identity Services](https://developers.google.com/identity/authorization/android)
- [YouTube Data API v3](https://developers.google.com/youtube/v3)

## License

[MIT](LICENSE)
