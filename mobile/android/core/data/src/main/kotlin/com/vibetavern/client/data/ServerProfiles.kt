package com.vibetavern.client.data

import com.vibetavern.client.network.ParsedServerAddress
import com.vibetavern.client.network.ServerAddress
import com.vibetavern.client.network.ServerConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * A server the app can talk to. The built-in [LOCAL] profile is the server this app runs
 * itself; every other profile is a remote server reached by address and access token.
 */
data class ServerProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    /** Mobile Access token. Always null for the local server (loopback needs none). */
    val token: String? = null,
) {
    val isLocal: Boolean get() = id == LOCAL_ID

    fun connection(): ServerConnection = ServerConnection(baseUrl, token)

    companion object {
        const val LOCAL_ID = "local"
        val LOCAL = ServerProfile(LOCAL_ID, "This device", ServerAddress.LOCAL_BASE_URL)
    }
}

data class ProfilesState(
    /** Remote profiles only; [ServerProfile.LOCAL] is always available in addition. */
    val remotes: List<ServerProfile> = emptyList(),
    val activeId: String? = null,
) {
    val all: List<ServerProfile> get() = listOf(ServerProfile.LOCAL) + remotes
    val active: ServerProfile? get() = all.firstOrNull { it.id == activeId }
}

/** Where profiles are persisted (a file on Android). */
interface TextStore {
    fun read(): String?
    fun write(text: String)
}

/** Protects access tokens at rest (Android Keystore on device). */
interface SecretCipher {
    fun encrypt(plain: String): String
    /** Null when the value cannot be decrypted (key lost, data corrupt); the profile then needs a new token. */
    fun decrypt(encrypted: String): String?
}

/**
 * Remote server profiles and which one is active.
 *
 * Tokens are stored encrypted ([SecretCipher]); everything else is plain JSON. A damaged
 * or unreadable store yields an empty state instead of a crash.
 */
class ServerProfileRepository(
    private val store: TextStore,
    private val cipher: SecretCipher,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<ProfilesState> = _state.asStateFlow()

    sealed interface AddResult {
        data class Added(val profile: ServerProfile) : AddResult
        /** The address was valid but already saved; its token (if newly supplied) was updated. */
        data class Updated(val profile: ServerProfile) : AddResult
        data object InvalidAddress : AddResult
    }

    /**
     * Adds a remote server from what the person typed or scanned. A QR link carries its
     * token; [tokenOverride] wins when the person typed one separately.
     */
    fun addRemote(input: String, name: String? = null, tokenOverride: String? = null): AddResult {
        val parsed: ParsedServerAddress = ServerAddress.parse(input) ?: return AddResult.InvalidAddress
        val token = tokenOverride?.trim()?.takeIf { it.isNotEmpty() } ?: parsed.token
        val existing = _state.value.remotes.firstOrNull { it.baseUrl.equals(parsed.baseUrl, ignoreCase = true) }
        val result: AddResult
        val profile: ServerProfile
        if (existing != null) {
            profile = existing.copy(
                token = token ?: existing.token,
                name = name?.trim()?.takeIf { it.isNotEmpty() } ?: existing.name,
            )
            result = AddResult.Updated(profile)
            _state.update { s -> s.copy(remotes = s.remotes.map { if (it.id == existing.id) profile else it }) }
        } else {
            profile = ServerProfile(
                id = newId(),
                name = name?.trim()?.takeIf { it.isNotEmpty() } ?: defaultName(parsed.baseUrl),
                baseUrl = parsed.baseUrl,
                token = token,
            )
            result = AddResult.Added(profile)
            _state.update { s -> s.copy(remotes = s.remotes + profile) }
        }
        persist()
        return result
    }

    fun updateToken(id: String, token: String?) {
        _state.update { s ->
            s.copy(remotes = s.remotes.map { if (it.id == id) it.copy(token = token?.trim()?.takeIf(String::isNotEmpty)) else it })
        }
        persist()
    }

    fun rename(id: String, name: String) {
        val clean = name.trim().ifEmpty { return }
        _state.update { s -> s.copy(remotes = s.remotes.map { if (it.id == id) it.copy(name = clean) else it }) }
        persist()
    }

    fun remove(id: String) {
        _state.update { s ->
            s.copy(remotes = s.remotes.filterNot { it.id == id }, activeId = s.activeId.takeUnless { it == id })
        }
        persist()
    }

    fun setActive(id: String?) {
        if (id != null && _state.value.all.none { it.id == id }) return
        _state.update { it.copy(activeId = id) }
        persist()
    }

    private fun defaultName(baseUrl: String): String =
        baseUrl.substringAfter("://").trimEnd('/')

    // ── Persistence ──────────────────────────────────────────────────────

    private fun persist() {
        val s = _state.value
        val json = buildJsonObject {
            put("version", 1)
            s.activeId?.let { put("activeId", it) }
            put(
                "remotes",
                JsonArray(
                    s.remotes.map { p ->
                        buildJsonObject {
                            put("id", p.id)
                            put("name", p.name)
                            put("baseUrl", p.baseUrl)
                            p.token?.let { put("token", cipher.encrypt(it)) }
                        }
                    },
                ),
            )
        }
        store.write(json.toString())
    }

    private fun load(): ProfilesState {
        val text = store.read() ?: return ProfilesState()
        return try {
            val root = Json.parseToJsonElement(text).jsonObject
            val remotes = root["remotes"]?.jsonArray.orEmpty().mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                val id = o.str("id") ?: return@mapNotNull null
                val baseUrl = o.str("baseUrl") ?: return@mapNotNull null
                if (id == ServerProfile.LOCAL_ID) return@mapNotNull null
                ServerProfile(
                    id = id,
                    name = o.str("name") ?: baseUrl,
                    baseUrl = baseUrl,
                    token = o.str("token")?.let(cipher::decrypt),
                )
            }
            val activeId = root.str("activeId")
            ProfilesState(remotes, activeId.takeIf { id -> id == ServerProfile.LOCAL_ID || remotes.any { it.id == id } })
        } catch (_: Exception) {
            ProfilesState()
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
