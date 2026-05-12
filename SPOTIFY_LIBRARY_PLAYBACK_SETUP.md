# Spotify Library / Playback Notes

This patch changes Spotify login to official OAuth Authorization Code + PKCE and turns the old import page into a Spotify-style library page.

## Required user setup

1. Create a Spotify Developer app.
2. Add this redirect URI exactly:

```text
echo-spotify-auth://callback
```

3. Copy the app's **Client ID**.
4. Open Echo → Spotify card → paste Client ID → Save ID → Login.

No Client Secret is used or stored in the APK.

## Playback behavior

Spotify Web API does not provide raw full-song stream URLs to third-party Android apps. Because of that:

- **Play** in the Echo Spotify page matches the Spotify track title/artist to Echo's existing playback pipeline.
- In your repo, that means Echo can still prefer your JioSaavn/YouTube fallback logic when resolving/playing matched tracks.
- **Spotify** button opens the Spotify URI in Spotify/browser for real Spotify playback.

If you want actual Spotify-controlled playback, you must integrate Spotify App Remote/Connect, which controls the installed Spotify app and has Premium/capability limitations.
