package iad1tya.echo.music.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.JsonObject
import iad1tya.echo.music.BuildConfig
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

object SpotifyAuthStore {
    private const val OAUTH_PREFS = "spotify_oauth_pkce_temp"
    private const val PendingVerifierKey = "code_verifier"
    private const val PendingStateKey = "state"
    private const val PendingClientIdKey = "client_id"

    const val REDIRECT_URI = "echo-spotify-auth://callback"

    val requiredScopes = listOf(
        "playlist-read-private",
        "playlist-read-collaborative",
        "user-library-read",
        "user-read-private",
        "user-read-email",
        "user-read-playback-state",
        "user-modify-playback-state",
        "app-remote-control",
    )

    data class SpotifyProfile(
        val id: String,
        val displayName: String,
        val email: String?,
        val imageUrl: String?,
    )

    private data class TokenResponse(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAtMs: Long,
    )

    private val client = OkHttpClient()
    private val gson = Gson()

    private val ClientIdKey = stringPreferencesKey("spotify_client_id")
    private val AccessTokenKey = stringPreferencesKey("spotify_access_token")
    private val RefreshTokenKey = stringPreferencesKey("spotify_refresh_token")
    private val ExpiresAtMsKey = longPreferencesKey("spotify_expires_at_ms")
    private val ProfileNameKey = stringPreferencesKey("spotify_profile_name")
    private val ProfileEmailKey = stringPreferencesKey("spotify_profile_email")
    private val ProfileIdKey = stringPreferencesKey("spotify_profile_id")
    private val ProfileImageKey = stringPreferencesKey("spotify_profile_image")

    suspend fun setClientId(context: Context, clientId: String) = withContext(Dispatchers.IO) {
        val clean = clientId.trim()
        context.dataStore.edit { prefs ->
            if (clean.isBlank()) prefs.remove(ClientIdKey) else prefs[ClientIdKey] = clean
        }
        context.oauthPrefs().edit().putString(PendingClientIdKey, clean).apply()
    }

    suspend fun getClientId(context: Context): String = withContext(Dispatchers.IO) {
        val stored = context.dataStore.data.first()[ClientIdKey].orEmpty().trim()
        stored.ifBlank { BuildConfig.SPOTIFY_CLIENT_ID.trim() }
    }

    suspend fun hasStoredToken(context: Context): Boolean = withContext(Dispatchers.IO) {
        val prefs = context.dataStore.data.first()
        !prefs[AccessTokenKey].isNullOrBlank() || !prefs[RefreshTokenKey].isNullOrBlank()
    }

    suspend fun getStoredDisplayName(context: Context): String = withContext(Dispatchers.IO) {
        context.dataStore.data.first()[ProfileNameKey].orEmpty()
    }

    fun createLoginIntent(context: Context, rawClientId: String = ""): Intent {
        val clientId = rawClientId.trim().ifBlank { getClientIdSync(context) }
        require(clientId.isNotBlank()) {
            "Spotify Client ID missing. Paste your Spotify Developer app Client ID first."
        }

        val verifier = randomBase64Url(64)
        val challenge = sha256Base64Url(verifier)
        val state = randomBase64Url(24)
        context.oauthPrefs().edit()
            .putString(PendingVerifierKey, verifier)
            .putString(PendingStateKey, state)
            .putString(PendingClientIdKey, clientId)
            .apply()

        val authorizeUrl = buildString {
            append("https://accounts.spotify.com/authorize")
            append("?response_type=code")
            append("&client_id=").append(url(clientId))
            append("&scope=").append(url(requiredScopes.joinToString(" ")))
            append("&redirect_uri=").append(url(REDIRECT_URI))
            append("&state=").append(url(state))
            append("&code_challenge_method=S256")
            append("&code_challenge=").append(url(challenge))
        }

        return Intent(Intent.ACTION_VIEW, Uri.parse(authorizeUrl)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    suspend fun handleRedirect(context: Context, uri: Uri?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(uri != null) { "Spotify redirect URI missing" }
            uri.getQueryParameter("error")?.let { error ->
                throw IOException("Spotify authorization failed: $error")
            }
            val code = uri.getQueryParameter("code") ?: throw IOException("Spotify authorization code missing")
            val state = uri.getQueryParameter("state") ?: throw IOException("Spotify state missing")
            val prefs = context.oauthPrefs()
            val expectedState = prefs.getString(PendingStateKey, null)
            val verifier = prefs.getString(PendingVerifierKey, null)
            val clientId = prefs.getString(PendingClientIdKey, null)?.trim().orEmpty()
            require(!expectedState.isNullOrBlank() && state == expectedState) {
                "Spotify login state mismatch. Try login again."
            }
            require(!verifier.isNullOrBlank()) { "Spotify PKCE verifier missing. Try login again." }
            require(clientId.isNotBlank()) { "Spotify Client ID missing. Try login again." }

            val token = exchangeCode(clientId, code, verifier)
            saveToken(context, clientId, token)
            fetchCurrentUser(context)
            prefs.edit().remove(PendingVerifierKey).remove(PendingStateKey).apply()
            Unit
        }
    }

    suspend fun getValidAccessToken(context: Context): String? = withContext(Dispatchers.IO) {
        val prefs = context.dataStore.data.first()
        val accessToken = prefs[AccessTokenKey]
        val expiresAt = prefs[ExpiresAtMsKey] ?: 0L
        val now = System.currentTimeMillis()
        if (!accessToken.isNullOrBlank() && expiresAt > now + 60_000L) {
            return@withContext accessToken
        }

        val refreshToken = prefs[RefreshTokenKey]?.takeIf { it.isNotBlank() } ?: return@withContext null
        val clientId = prefs[ClientIdKey]?.takeIf { it.isNotBlank() } ?: BuildConfig.SPOTIFY_CLIENT_ID.trim()
        if (clientId.isBlank()) return@withContext null

        return@withContext try {
            val refreshed = refreshAccessToken(clientId, refreshToken)
            saveToken(context, clientId, refreshed.copy(refreshToken = refreshed.refreshToken ?: refreshToken))
            refreshed.accessToken
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchCurrentUser(context: Context): SpotifyProfile? = withContext(Dispatchers.IO) {
        val token = getValidAccessToken(context) ?: return@withContext storedProfile(context)
        val request = Request.Builder()
            .url("https://api.spotify.com/v1/me")
            .header("Authorization", "Bearer $token")
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) return@withContext storedProfile(context)
            val json = gson.fromJson(body, JsonObject::class.java)
            val imageUrl = json.getAsJsonArray("images")?.firstOrNull()?.asJsonObject?.optString("url")
            val profile = SpotifyProfile(
                id = json.optString("id").orEmpty(),
                displayName = json.optString("display_name").ifBlankOrNull { json.optString("id").orEmpty() },
                email = json.optString("email"),
                imageUrl = imageUrl,
            )
            context.dataStore.edit { prefs ->
                prefs[ProfileIdKey] = profile.id
                prefs[ProfileNameKey] = profile.displayName
                profile.email?.let { prefs[ProfileEmailKey] = it } ?: prefs.remove(ProfileEmailKey)
                profile.imageUrl?.let { prefs[ProfileImageKey] = it } ?: prefs.remove(ProfileImageKey)
            }
            profile
        }
    }

    suspend fun clearAuth(context: Context) = withContext(Dispatchers.IO) {
        context.dataStore.edit { prefs ->
            prefs.remove(AccessTokenKey)
            prefs.remove(RefreshTokenKey)
            prefs.remove(ExpiresAtMsKey)
            prefs.remove(ProfileNameKey)
            prefs.remove(ProfileEmailKey)
            prefs.remove(ProfileIdKey)
            prefs.remove(ProfileImageKey)
        }
        context.oauthPrefs().edit().clear().apply()
    }

    private suspend fun saveToken(context: Context, clientId: String, token: TokenResponse) {
        context.dataStore.edit { prefs ->
            prefs[ClientIdKey] = clientId
            prefs[AccessTokenKey] = token.accessToken
            token.refreshToken?.let { prefs[RefreshTokenKey] = it }
            prefs[ExpiresAtMsKey] = token.expiresAtMs
        }
        context.oauthPrefs().edit().putString(PendingClientIdKey, clientId).apply()
    }

    private fun exchangeCode(clientId: String, code: String, verifier: String): TokenResponse {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("client_id", clientId)
            .add("code_verifier", verifier)
            .build()
        return executeTokenRequest(body)
    }

    private fun refreshAccessToken(clientId: String, refreshToken: String): TokenResponse {
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .build()
        return executeTokenRequest(body)
    }

    private fun executeTokenRequest(body: FormBody): TokenResponse {
        val request = Request.Builder()
            .url("https://accounts.spotify.com/api/token")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Spotify token request failed: HTTP ${response.code} ${responseBody.take(200)}")
            }
            val json = gson.fromJson(responseBody, JsonObject::class.java)
            val accessToken = json.optString("access_token") ?: throw IOException("Spotify token response missing access_token")
            val expiresIn = json.optLong("expires_in", 3600L)
            return TokenResponse(
                accessToken = accessToken,
                refreshToken = json.optString("refresh_token"),
                expiresAtMs = System.currentTimeMillis() + (expiresIn * 1000L),
            )
        }
    }

    private suspend fun storedProfile(context: Context): SpotifyProfile? {
        val prefs = context.dataStore.data.first()
        val id = prefs[ProfileIdKey].orEmpty()
        val name = prefs[ProfileNameKey].orEmpty()
        if (id.isBlank() && name.isBlank()) return null
        return SpotifyProfile(
            id = id,
            displayName = name.ifBlank { "Spotify" },
            email = prefs[ProfileEmailKey],
            imageUrl = prefs[ProfileImageKey],
        )
    }

    private fun Context.oauthPrefs() = getSharedPreferences(OAUTH_PREFS, Context.MODE_PRIVATE)

    private fun getClientIdSync(context: Context): String =
        context.oauthPrefs().getString(PendingClientIdKey, null).orEmpty().trim()
            .ifBlank { BuildConfig.SPOTIFY_CLIENT_ID.trim() }

    private fun randomBase64Url(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun sha256Base64Url(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun url(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun JsonObject.optString(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.asString

    private fun JsonObject.optLong(key: String, default: Long): Long =
        get(key)?.takeUnless { it.isJsonNull }?.asLong ?: default

    private fun String?.ifBlankOrNull(block: () -> String): String =
        if (this.isNullOrBlank()) block() else this
}
