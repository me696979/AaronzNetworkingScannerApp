# Tri-State Scanner Android
Native Android prototype for https://scannerlive.aaronznetworking.net.

## v0.1 goals
- Start at current live edge (no old-call replay on startup)
- Play queued scanner MP3 calls with Media3/ExoPlayer
- Background/lock-screen playback via MediaSessionService
- Now Playing, transcript, active 2-hour alerts, listener/peak counters
- Uses the existing HTTPS scanner API

## Build without Android Studio
The included GitHub Actions workflow builds `app-debug.apk` on push or manual dispatch.
