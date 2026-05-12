package iad1tya.echo.music.spotify

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import iad1tya.echo.music.MainActivity
import iad1tya.echo.music.utils.SpotifyAuthStore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SpotifyBrowserLoginActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var serverSocket: ServerSocket? = null
    private lateinit var status: TextView
    private var loginStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.rgb(18, 18, 18))
            setPadding(28, 36, 28, 28)
        }

        status = TextView(this).apply {
            text = "Preparing Spotify login..."
            setTextColor(Color.WHITE)
            textSize = 15f
            setLineSpacing(4f, 1.05f)
        }
        root.addView(status, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val loginAgain = Button(this).apply {
            text = "Open Spotify Login"
            setOnClickListener { startLogin(force = true) }
        }
        root.addView(loginAgain, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val close = Button(this).apply {
            text = "Close"
            setOnClickListener { finish() }
        }
        root.addView(close, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        setContentView(root)
        startLogin(force = false)
    }

    private fun startLogin(force: Boolean) {
        if (loginStarted && !force) return
        loginStarted = true
        closeServer()

        scope.launch {
            try {
                status.text = "Starting local Spotify OAuth callback..."
                val server = withContext(Dispatchers.IO) {
                    ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply {
                        soTimeout = 180_000
                    }
                }
                serverSocket = server
                val redirectUri = "http://127.0.0.1:${server.localPort}/callback"
                val clientId = intent.getStringExtra(SpotifyAuthStore.EXTRA_CLIENT_ID).orEmpty()
                val loginUri = SpotifyAuthStore.prepareLogin(
                    context = this@SpotifyBrowserLoginActivity,
                    rawClientId = clientId,
                    redirectUri = redirectUri,
                )

                status.text = "A browser tab is opening. Complete Spotify login there.\n\nIn Spotify Developer Dashboard, the app must include this Redirect URI:\nhttp://127.0.0.1/callback"
                openBrowser(loginUri)

                val callbackUri = waitForSpotifyCallback(server, redirectUri)
                status.text = "Completing Spotify login..."
                val result = SpotifyAuthStore.handleRedirect(this@SpotifyBrowserLoginActivity, callbackUri)
                if (result.isSuccess) {
                    SpotifyAuthStore.fetchCurrentUser(this@SpotifyBrowserLoginActivity)
                    Toast.makeText(this@SpotifyBrowserLoginActivity, "Spotify connected", Toast.LENGTH_SHORT).show()
                    startActivity(
                        Intent(this@SpotifyBrowserLoginActivity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                    finish()
                } else {
                    status.text = "Spotify login failed: ${result.exceptionOrNull()?.message ?: "unknown error"}\n\nCheck Client ID and Redirect URI, then tap Open Spotify Login again."
                }
            } catch (e: ActivityNotFoundException) {
                status.text = "No browser app could open Spotify login: ${e.message}"
            } catch (e: SocketTimeoutException) {
                status.text = "Spotify login timed out. Tap Open Spotify Login and finish the login flow again."
            } catch (e: Exception) {
                status.text = "Spotify login failed: ${e.message}\n\nMake sure your Spotify app has Redirect URI http://127.0.0.1/callback and your Client ID is correct."
            } finally {
                closeServer()
            }
        }
    }

    private fun openBrowser(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private suspend fun waitForSpotifyCallback(server: ServerSocket, redirectUri: String): Uri = withContext(Dispatchers.IO) {
        server.use { listeningSocket ->
            val socket = listeningSocket.accept()
            socket.use { clientSocket ->
                val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
                val firstLine = reader.readLine().orEmpty()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) break
                }

                val requestedPath = firstLine.substringAfter(" ", "").substringBefore(" ", "")
                val callbackUri = buildCallbackUri(redirectUri, requestedPath)
                val html = if (callbackUri.getQueryParameter("code") != null) {
                    "<html><body style='font-family:sans-serif;background:#121212;color:white'><h2>Spotify connected</h2><p>You can return to Echo Music.</p></body></html>"
                } else {
                    "<html><body style='font-family:sans-serif;background:#121212;color:white'><h2>Spotify login received</h2><p>Return to Echo Music to see the result.</p></body></html>"
                }
                val bytes = html.toByteArray(Charsets.UTF_8)
                val response = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: text/html; charset=utf-8\r\n")
                    append("Content-Length: ${bytes.size}\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }.toByteArray(Charsets.UTF_8)
                clientSocket.getOutputStream().write(response)
                clientSocket.getOutputStream().write(bytes)
                clientSocket.getOutputStream().flush()
                callbackUri
            }
        }
    }

    private fun buildCallbackUri(redirectUri: String, requestedPath: String): Uri {
        val query = requestedPath.substringAfter('?', missingDelimiterValue = "")
        return if (query.isBlank()) Uri.parse(redirectUri) else Uri.parse("$redirectUri?$query")
    }

    private fun closeServer() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    override fun onDestroy() {
        closeServer()
        scope.cancel()
        super.onDestroy()
    }
}
