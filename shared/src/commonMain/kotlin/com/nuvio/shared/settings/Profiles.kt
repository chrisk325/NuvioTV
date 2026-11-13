package com.nuvio.shared.settings

import com.nuvio.shared.storage.KeyValueStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class UserProfile(
    /** 1 is the primary profile (also the Nuvio account's profile_id). */
    val index: Int,
    val name: String,
    val colorHex: String = "#1E88E5",
    val pin: String = ""
) {
    val isLocked: Boolean get() = pin.isNotEmpty()
    val isPrimary: Boolean get() = index == 1
}

/** Device-wide profile list (port of ProfileManager, local part). Stored outside any profile. */
class ProfileStore(private val store: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }

    val profiles: List<UserProfile>
        get() = store.getString(KEY)?.let {
            runCatching { json.decodeFromString(ListSerializer(UserProfile.serializer()), it) }.getOrNull()
        }?.takeIf { it.isNotEmpty() } ?: listOf(UserProfile(1, "Main"))

    private fun save(list: List<UserProfile>) =
        store.putString(KEY, json.encodeToString(ListSerializer(UserProfile.serializer()), list.sortedBy { it.index }))

    var lastProfileIndex: Int
        get() = store.getString("profiles.last")?.toIntOrNull()?.takeIf { i -> profiles.any { it.index == i } } ?: 1
        set(value) = store.putString("profiles.last", value.toString())

    /** Ask "Who's watching?" on launch when there is more than one profile. */
    val shouldAskOnLaunch: Boolean get() = profiles.size > 1

    fun add(name: String, colorHex: String): UserProfile {
        val index = (profiles.maxOfOrNull { it.index } ?: 1) + 1
        val profile = UserProfile(index, name.ifBlank { "Profile $index" }, colorHex)
        save(profiles + profile)
        return profile
    }

    fun update(profile: UserProfile) = save(profiles.map { if (it.index == profile.index) profile else it })

    fun rename(index: Int, name: String) =
        profiles.firstOrNull { it.index == index }?.let { update(it.copy(name = name.ifBlank { it.name })) }

    fun setPin(index: Int, pin: String) =
        profiles.firstOrNull { it.index == index }?.let { update(it.copy(pin = pin.filter(Char::isDigit).take(8))) }

    fun remove(index: Int) {
        if (index == 1) return
        save(profiles.filterNot { it.index == index })
    }

    fun checkPin(index: Int, pin: String): Boolean = profiles.firstOrNull { it.index == index }?.pin.orEmpty().let { it.isEmpty() || it == pin }

    private companion object { const val KEY = "profiles.v1" }
}
