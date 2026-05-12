package iad1tya.echo.music.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.JsonObject
import iad1tya.echo.music.BuildConfig
import iad1tya.echo.music.spotify.SpotifyBrowserLoginActivity
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

object SpotifyAuthStore {
    private const val TAG = "SpotifyAuthStore"
    const val REDIRECT_URI = "echo-spotify-auth://callback"
    const val EXTRA_CLIENT_ID = "iad1tya.echo.music.spotify.EXTRA_CLIENT_ID"

    private val USER_AGENT =
        "EchoMusic/4.2 Android (${BuildConfig.APPLICATION_ID}; ${BuildConfig.ARCHITECTURE})"

    private val client = OkHttpClient()
    private val gson = Gson()
    private val random = SecureRandom()

    private val ClientIdKey = stringPreferencesKey("spotify_oauth_client_id")
    private val AccessTokenKey = stringPreferencesKey("spotify_oauth_access_token")
    private val RefreshTokenKey = stringPreferencesKey("spotify_oauth_refresh_token")
    private val ExpiresAtMsKey = longPreferencesKey("spotify_oauth_expires_at_ms")
    private val ScopeKey = stringPreferencesKey("spotify_oauth_scope")
    private val CodeVerifierKey = stringPreferencesKey("spotify_oauth_code_verifier")
    private val StateKey = stringPreferencesKey("spotify_oauth_state")
    private val RedirectUriKey = stringPreferencesKey("spotify_oauth_redirect_uri")
    private val ProfileNameKey = stringPreferencesKey("spotify_profile_name")
    private val ProfileEmailKey = stringPreferencesKey("spotify_profile_email")
    private val ProfileIdKey = stringPreferencesKey("spotify_profile_id")
    private val ProfileImageKey = stringPreferencesKey("spotify_profile_image")

    // Old browser-cookie keys from previous patches. Cleared during sign out so stale broken sessions do not survive.
    private val OldBrowserCookieKey = stringPreferencesKey("spotify_browser_cookie")
    private val OldWebAccessTokenKey = stringPreferencesKey("spotify_web_access_token")
    private val OldWebExpiresAtKey = longPreferencesKey("spotify_web_expires_at_ms")

    val requiredScopes = listOf(
        "playlist-read-private",
        "playlist-read-collaborative",
        "user-library-read",
        "user-read-private",
        "user-read-email",
    )

    data class SpotifyProfile(
        val id: String,
        val displayName: String,
        val email: String?,
        val imageUrl: String?,
    )

    suspend fun setClientId(context: Context, clientId: String) = withContext(Dispatchers.IO) {
        val cleaned = clientId.trim()
        context.dataStore.edit { prefs ->
            if (cleaned.isBlank()) prefs.remove(ClientIdKey) else prefs[ClientIdKey] = cleaned
        }
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

    fun createLoginIntent(context: Context, rawClientId: String = ""): Intent =
        Intent(context, SpotifyBrowserLoginActivity::class.java)
            .putExtra(EXTRA_CLIENT_ID, rawClientId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun prepareLogin(context: Context, rawClientId: String, redirectUri: String): Uri = withContext(Dispatchers.IO) {
        val clientId = rawClientId.trim().ifBlank { getClientId(context) }
        require(clientId.isNotBlank()) {
            "Spotify Client ID is missing. Create a Spotify Developer app, add http://127.0.0.1/callback as Redirect URI, then paste the Client ID."
        }
        require(redirectUri.isNotBlank()) { "Spotify redirect URI is missing" }

        setClientId(context, clientId)
        val state = randomUrlSafe(24)
        val verifier = randomUrlSafe(64)
        val challenge = codeChallengeS256(verifier)
        val scopeText = requiredScopes.joinToString(" ")

        context.dataStore.edit { prefs ->
            prefs[StateKey] = state
            prefs[CodeVerifierKey] = verifier
            prefs[RedirectUriKey] = redirectUri
            prefs[ScopeKey] = scopeText
            prefs.remove(AccessTokenKey)
            prefs.remove(ExpiresAtMsKey)
        }

        Uri.Builder()
            .scheme("https")
            .authority("accounts.spotify.com")
            .path("authorize")
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("scope", scopeText)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .build()
    }

    suspend fun handleRedirect(context: Context, uri: Uri?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(uri != null) { "Missing Spotify redirect URI" }
            uri.getQueryParameter("error")?.takeIf { it.isNotBlank() }?.let { error ->
                throw IllegalStateException("Spotify login failed: $error")
            }
            val code = uri.getQueryParameter("code") ?: throw IllegalStateException("Spotify login returned no authorization code")
            val returnedState = uri.getQueryParameter("state") ?: throw IllegalStateException("Spotify login returned no state")

            val prefs = context.dataStore.data.first()
            val expectedState = prefs[StateKey] ?: throw IllegalStateException("Missing saved Spotify login state")
            val verifier = prefs[CodeVerifierKey] ?: throw IllegalStateException("Missing saved Spotify PKCE verifier")
            val redirectUri = prefs[RedirectUriKey] ?: throw IllegalStateException("Missing saved Spotify redirect URI")
            val clientId = prefs[ClientIdKey]?.takeIf { it.isNotBlank() }
                ?: BuildConfig.SPOTIFY_CLIENT_ID.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Missing Spotify Client ID")

            check(returnedState == expectedState) { "Spotify login state mismatch" }

            val tokenJson = exchangeAuthorizationCode(clientId, code, verifier, redirectUri)
            saveTokenResponse(context, tokenJson, existingRefreshToken = null)
            context.dataStore.edit { editPrefs ->
                editPrefs.remove(StateKey)
                editPrefs.remove(CodeVerifierKey)
                editPrefs.remove(RedirectUriKey)
                editPrefs.remove(OldBrowserCookieKey)
                editPrefs.remove(OldWebAccessTokenKey)
                editPrefs.remove(OldWebExpiresAtKey)
            }
            fetchCurrentUser(context)
            Unit
        }
    }

    suspend fun getValidAccessToken(context: Context): String? = withContext(Dispatchers.IO) {
        val prefs = context.dataStore.data.first()
        val storedToken = prefs[AccessTokenKey]
        val expiresAt = prefs[ExpiresAtMsKey] ?: 0L
        val now = System.currentTimeMillis()
        if (!storedToken.isNullOrBlank() && expiresAt > now + 60_000L) {
            return@withContext storedToken
        }

        val refreshToken = prefs[RefreshTokenKey]?.takeIf { it.isNotBlank() } ?: return@withContext null
        val clientId = prefs[ClientIdKey]?.takeIf { it.isNotBlank() }
            ?: BuildConfig.SPOTIFY_CLIENT_ID.takeIf { it.isNotBlank() }
            ?: return@withContext null

        try {
            val tokenJson = refreshAccessToken(clientId, refreshToken)
            saveTokenResponse(context, tokenJson, existingRefreshToken = refreshToken)
            context.dataStore.data.first()[AccessTokenKey]
        } catch (e: Exception) {
            Log.w(TAG, "Spotify token refresh failed", e)
            null
        }
    }

    suspend fun fetchCurrentUser(context: Context): SpotifyProfile? = withContext(Dispatchers.IO) {
        val token = getValidAccessToken(context) ?: return@withContext storedProfile(context)
        val request = Request.Builder()
            .url("https://api.spotify.com/v1/me")
            .header("Authorization", "Bearer $token")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.w(TAG, "Failed to fetch Spotify profile: HTTP ${response.code} ${body.take(160)}")
                return@withContext storedProfile(context)
            }
            val json = gson.fromJson(body, JsonObject::class.java)
            val images = json.getAsJsonArray("images")
            val imageUrl = images?.firstOrNull()?.asJsonObject?.optString("url")
            val profile = SpotifyProfile(
                id = json.optString("id").orEmpty(),
                displayName = json.optString("display_name").ifBlankOrNull { json.optString("id").orEmpty().ifBlank { "Spotify" } },
                email = json.optString("email"),
                imageUrl = imageUrl,
            )
            context.dataStore.edit { prefs ->
                prefs[ProfileIdKey] = profile.id
                prefs[ProfileNameKey] = profile.displayName
                profile.email?.takeIf { it.isNotBlank() }?.let { prefs[ProfileEmailKey] = it } ?: prefs.remove(ProfileEmailKey)
                profile.imageUrl?.takeIf { it.isNotBlank() }?.let { prefs[ProfileImageKey] = it } ?: prefs.remove(ProfileImageKey)
            }
            profile
        }
    }

    suspend fun clearAuth(context: Context) = withContext(Dispatchers.IO) {
        context.dataStore.edit { prefs ->
            prefs.remove(AccessTokenKey)
            prefs.remove(RefreshTokenKey)
            prefs.remove(ExpiresAtMsKey)
            prefs.remove(ScopeKey)
            prefs.remove(CodeVerifierKey)
            prefs.remove(StateKey)
            prefs.remove(RedirectUriKey)
            prefs.remove(ProfileNameKey)
            prefs.remove(ProfileEmailKey)
            prefs.remove(ProfileIdKey)
            prefs.remove(ProfileImageKey)
            prefs.remove(OldBrowserCookieKey)
            prefs.remove(OldWebAccessTokenKey)
            prefs.remove(OldWebExpiresAtKey)
        }
    }

    private fun exchangeAuthorizationCode(clientId: String, code: String, verifier: String, redirectUri: String): JsonObject {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("client_id", clientId)
            .add("code_verifier", verifier)
            .build()
        return postTokenForm(form, "Spotify token exchange failed")
    }

    private fun refreshAccessToken(clientId: String, refreshToken: String): JsonObject {
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .build()
        return postTokenForm(form, "Spotify token refresh failed")
    }

    private fun postTokenForm(form: FormBody, failureLabel: String): JsonObject {
        val request = Request.Builder()
            .url("https://accounts.spotify.com/api/token")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .post(form)
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("$failureLabel: HTTP ${response.code} ${body.take(240)}")
            }
            return gson.fromJson(body, JsonObject::class.java)
        }
    }

    private suspend fun saveTokenResponse(context: Context, tokenJson: JsonObject, existingRefreshToken: String?) {
        val accessToken = tokenJson.optString("access_token")?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Spotify response missing access_token")
        val refreshToken = tokenJson.optString("refresh_token")?.takeIf { it.isNotBlank() } ?: existingRefreshToken
        val scope = tokenJson.optString("scope") ?: requiredScopes.joinToString(" ")
        val expiresInSeconds = tokenJson.optLong("expires_in", 3600L).coerceAtLeast(60L)
        val expiresAtMs = System.currentTimeMillis() + expiresInSeconds * 1000L

        context.dataStore.edit { prefs ->
            prefs[AccessTokenKey] = accessToken
            if (!refreshToken.isNullOrBlank()) prefs[RefreshTokenKey] = refreshToken
            prefs[ExpiresAtMsKey] = expiresAtMs
            prefs[ScopeKey] = scope
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

    private fun randomUrlSafe(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun codeChallengeS256(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun JsonObject.optString(key: String): String? =
        get(key)?.takeUnless { it.isJsonNull }?.asString

    private fun JsonObject.optLong(key: String, default: Long): Long =
        get(key)?.takeUnless { it.isJsonNull }?.asLong ?: default

    private inline fun String?.ifBlankOrNull(fallback: () -> String): String =
        if (this.isNullOrBlank()) fallback() else this
}
