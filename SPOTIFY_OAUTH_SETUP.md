# Spotify OAuth setup for Echo Music

This repo uses Spotify Authorization Code with PKCE. It is meant for importing Spotify playlist/liked-song metadata into Echo; playback still goes through Echo's normal JioSaavn/YouTube flow.

## Required Spotify dashboard setting

Create a Spotify Developer app and add this Redirect URI:

```text
http://127.0.0.1/callback
```

The app starts a temporary local callback server on Android and sends Spotify a dynamic port such as `http://127.0.0.1:43125/callback`.

## Client ID

No client secret is required. Provide the Client ID in either place:

1. Build time:

```bash
./gradlew assembleUniversalDebug -PSPOTIFY_CLIENT_ID='your_client_id'
```

or GitHub Actions:

```yaml
env:
  SPOTIFY_CLIENT_ID: ${{ secrets.SPOTIFY_CLIENT_ID }}
```

2. Runtime:

Open **Spotify Library** in the app and paste the Client ID.

## Scopes requested

- `playlist-read-private`
- `playlist-read-collaborative`
- `user-library-read`
- `user-read-private`
- `user-read-email`

## Important

If your Spotify app is still in Development Mode, add your Spotify account under the app's allowed users in the Spotify Developer Dashboard.
