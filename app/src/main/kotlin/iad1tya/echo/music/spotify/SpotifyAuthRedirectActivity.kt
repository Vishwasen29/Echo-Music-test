package iad1tya.echo.music.spotify

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import iad1tya.echo.music.MainActivity
import iad1tya.echo.music.utils.SpotifyAuthStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SpotifyAuthRedirectActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleCurrentIntent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCurrentIntent()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun handleCurrentIntent() {
        val redirectUri = intent?.data
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                SpotifyAuthStore.handleRedirect(this@SpotifyAuthRedirectActivity, redirectUri)
            }
            if (result.isSuccess) {
                withContext(Dispatchers.IO) {
                    SpotifyAuthStore.fetchCurrentUser(this@SpotifyAuthRedirectActivity)
                }
            }
            Toast.makeText(
                this@SpotifyAuthRedirectActivity,
                result.fold(
                    onSuccess = { "Spotify connected" },
                    onFailure = { "Spotify login failed: ${it.message}" },
                ),
                Toast.LENGTH_LONG,
            ).show()
            startActivity(
                Intent(this@SpotifyAuthRedirectActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            finish()
        }
    }
}
