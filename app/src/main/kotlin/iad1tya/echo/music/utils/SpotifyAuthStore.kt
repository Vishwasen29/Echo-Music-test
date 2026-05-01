package iad1tya.echo.music.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.JsonObject
import iad1tya.echo.music.BuildConfig
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

object SpotifyAuthStore {
    private const val TAG = "SpotifyAuthStore"
    const val REDIRECT_URI = "echo-spotify-auth://callback"

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
    private val ProfileNameKey = stringPreferencesKey("spotify_profile_name")
    private val ProfileEmailKey = stringPreferencesKey("spotify_profile_email")
    private val ProfileIdKey = stringPreferencesKey("spotify_profile_id")
    private val ProfileImageKey = stringPreferencesKey("spotify_profile_image")

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

    suspend fun setClientId(context: Context, clientId: String) {
        withContext(Dispatchers.IO) {
            context.dataStore.edit { prefs ->
                if (clientId.isBlank()) {
                    prefs.remove(ClientIdKey)
                } else {
                    prefs[ClientIdKey] = clientId.trim()
                }
            }
        }
    }

    suspend fun getClientId(context: Context): String = withContext(Dispatchers.IO) {
        val stored = context.dataStore.data.first()[ClientIdKey]?.trim().orEmpty()
        stored.ifBlank { BuildConfig.SPOTIFY_CLIENT_ID.trim() }
    }

    suspend fun hasStoredToken(context: Context): Boolean = withContext(Dispatchers.IO) {
        val prefs = context.dataStore.data.first()
        !prefs[AccessTokenKey].isNullOrBlank() || !prefs[RefreshTokenKey].isNullOrBlank()
    }

    suspend fun getStoredDisplayName(context: Context): String = withContext(Dispatchers.IO) {
        context.dataStore.data.first()[ProfileNameKey].orEmpty()
    }

    suspend fun createLoginIntent(context: Context, rawClientId: String): Intent = withContext(Dispatchers.IO) {
        val clientId = rawClientId.trim().ifBlank { getClientId(context) }
        require(clientId.isNotBlank()) { "Spotify Client ID is required" }

        val codeVerifier = randomUrlSafeString(64)
        val codeChallenge = codeChallengeS256(codeVerifier)
        val state = randomUrlSafeString(32)

        context.dataStore.edit { prefs ->
            prefs[ClientIdKey] = clientId
            prefs[CodeVerifierKey] = codeVerifier
            prefs[StateKey] = state
        }

        val uri = Uri.Builder()
            .scheme("https")
            .authority("accounts.spotify.com")
            .path("authorize")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", requiredScopes.joinToString(" "))
            .build()

        Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    suspend fun handleRedirect(context: Context, uri: Uri?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(uri != null) { "Missing Spotify redirect URI" }
            uri.getQueryParameter("error")?.let { error ->
                throw IllegalStateException("Spotify login failed: $error")
            }
            val code = uri.getQueryParameter("code") ?: throw IllegalStateException("Spotify login returned no authorization code")
            val returnedState = uri.getQueryParameter("state") ?: throw IllegalStateException("Spotify login returned no state")

            val prefs = context.dataStore.data.first()
            val expectedState = prefs[StateKey] ?: throw IllegalStateException("Missing saved Spotify login state")
            val verifier = prefs[CodeVerifierKey] ?: throw IllegalStateException("Missing saved Spotify PKCE verifier")
            val clientId = prefs[ClientIdKey]?.takeIf { it.isNotBlank() }
                ?: BuildConfig.SPOTIFY_CLIENT_ID.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Missing Spotify Client ID")

            check(returnedState == expectedState) { "Spotify login state mismatch" }
            exchangeCodeForTokens(context, clientId, verifier, code)
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

        val refreshToken = prefs[RefreshTokenKey]
        val clientId = prefs[ClientIdKey]?.takeIf { it.isNotBlank() }
            ?: BuildConfig.SPOTIFY_CLIENT_ID.takeIf { it.isNotBlank() }
        if (refreshToken.isNullOrBlank() || clientId.isNullOrBlank()) {
            return@withContext null
        }

        refreshAccessToken(context, clientId, refreshToken)
    }

    suspend fun fetchCurrentUser(context: Context): SpotifyProfile? = withContext(Dispatchers.IO) {
        val token = getValidAccessToken(context) ?: return@withContext null
        val request = Request.Builder()
            .url("https://api.spotify.com/v1/me")
            .header("Authorization", "Bearer $token")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "Failed to fetch Spotify profile: HTTP ${response.code}")
                return@withContext null
            }
            val json = gson.fromJson(response.body?.string().orEmpty(), JsonObject::class.java)
            val images = json.getAsJsonArray("images")
            val imageUrl = images?.firstOrNull()?.asJsonObject?.get("url")?.takeUnless { it.isJsonNull }?.asString
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
            prefs.remove(ScopeKey)
            prefs.remove(CodeVerifierKey)
            prefs.remove(StateKey)
            prefs.remove(ProfileNameKey)
            prefs.remove(ProfileEmailKey)
            prefs.remove(ProfileIdKey)
            prefs.remove(ProfileImageKey)
        }
    }

    private fun exchangeCodeForTokens(context: Context, clientId: String, codeVerifier: String, code: String) {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("code_verifier", codeVerifier)
            .build()

        val request = Request.Builder()
            .url("https://accounts.spotify.com/api/token")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Spotify token exchange failed: HTTP ${response.code} ${responseBody.take(240)}")
            }
            val json = gson.fromJson(responseBody, JsonObject::class.java)
            saveTokenResponse(context, json, preserveRefreshToken = null)
        }
    }

    private fun refreshAccessToken(context: Context, clientId: String, refreshToken: String): String? {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()

        val request = Request.Builder()
            .url("https://accounts.spotify.com/api/token")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Spotify token refresh failed: HTTP ${response.code} ${responseBody.take(160)}")
                    return null
                }
                val json = gson.fromJson(responseBody, JsonObject::class.java)
                saveTokenResponse(context, json, preserveRefreshToken = refreshToken)
                json.optString("access_token")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Spotify token refresh failed", e)
            null
        }
    }

    private fun saveTokenResponse(context: Context, json: JsonObject, preserveRefreshToken: String?) {
        val accessToken = json.optString("access_token") ?: throw IllegalStateException("Spotify response missing access_token")
        val refreshToken = json.optString("refresh_token") ?: preserveRefreshToken
        val expiresInSeconds = json.optLong("expires_in", 3600L).coerceAtLeast(60L)
        val expiresAt = System.currentTimeMillis() + (expiresInSeconds * 1000L)
        val scope = json.optString("scope") ?: requiredScopes.joinToString(" ")

        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            context.dataStore.edit { prefs ->
                prefs[AccessTokenKey] = accessToken
                if (!refreshToken.isNullOrBlank()) prefs[RefreshTokenKey] = refreshToken
                prefs[ExpiresAtMsKey] = expiresAt
                prefs[ScopeKey] = scope
                prefs.remove(CodeVerifierKey)
                prefs.remove(StateKey)
            }
        }
    }

    private fun randomUrlSafeString(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun codeChallengeS256(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun JsonObject.optString(key: String): String? =
        get(key)?.takeUnless { it.isJsonNull }?.asString

    private fun JsonObject.optLong(key: String, default: Long): Long =
        get(key)?.takeUnless { it.isJsonNull }?.asLong ?: default

    private inline fun String?.ifBlankOrNull(fallback: () -> String): String =
        if (this.isNullOrBlank()) fallback() else this
}
