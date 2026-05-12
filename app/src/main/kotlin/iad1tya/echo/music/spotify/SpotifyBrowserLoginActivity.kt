package iad1tya.echo.music.spotify

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import iad1tya.echo.music.utils.SpotifyAuthStore

/**
 * Kept only for compatibility with older call sites/back stack entries.
 * The repaired Spotify login uses official OAuth PKCE in the system browser.
 */
class SpotifyBrowserLoginActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            startActivity(SpotifyAuthStore.createLoginIntent(this, ""))
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Spotify login failed", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
