package com.nuvio.tv.data.mediaserver.jellyfin

import com.nuvio.tv.data.mediaserver.ServerTitleQuery
import com.nuvio.tv.data.mediaserver.mediabrowser.Endpoint
import com.nuvio.tv.data.mediaserver.mediabrowser.MediaBrowserProvider
import com.nuvio.tv.data.mediaserver.mediabrowser.ServerClientIdentity
import com.nuvio.tv.data.mediaserver.mediabrowser.pathSegment
import okhttp3.OkHttpClient

internal class JellyfinProvider(
    http: OkHttpClient,
    identity: ServerClientIdentity
) : MediaBrowserProvider(authorizationHeader = "Authorization", apiPath = "", http = http, identity = identity) {
    override val id: String = "jellyfin"
    override val displayName: String = "Jellyfin"
    override val minimumVersion: String = "10.9"

    override fun viewsEndpoint(userId: String) = Endpoint("/UserViews", mapOf("userId" to userId))

    override fun itemsEndpoint(userId: String) = Endpoint("/Items", mapOf("userId" to userId))

    override fun itemEndpoint(userId: String, itemId: String) =
        Endpoint("/Items/${pathSegment(itemId)}", mapOf("userId" to userId))

    override fun resumeEndpoint(userId: String) = Endpoint("/UserItems/Resume", mapOf("userId" to userId))

    override fun playedEndpoint(userId: String, itemId: String) =
        Endpoint("/UserPlayedItems/${pathSegment(itemId)}", mapOf("userId" to userId))

    override fun favoriteEndpoint(userId: String, itemId: String) =
        Endpoint("/UserFavoriteItems/${pathSegment(itemId)}", mapOf("userId" to userId))

    // Resume lists items with a saved position, so clearing the position takes it out; played and favourite stay.
    override fun removeFromResumeRequest(userId: String, itemId: String) =
        Endpoint("/UserItems/${pathSegment(itemId)}/UserData", mapOf("userId" to userId)) to """{"PlaybackPositionTicks":0}"""

    override fun titleLookupQuery(query: ServerTitleQuery): Map<String, String>? =
        query.name?.trim()?.takeIf { it.isNotEmpty() }?.let { mapOf("searchTerm" to it) }

    override fun titleRetryQuery(query: ServerTitleQuery): Map<String, String>? =
        query.originalName?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals(query.name?.trim(), ignoreCase = true) }
            ?.let { mapOf("searchTerm" to it) }
}
