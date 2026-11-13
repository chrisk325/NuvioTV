package com.nuvio.shared.repository

import com.nuvio.shared.data.StremioClient
import com.nuvio.shared.model.Addon
import com.nuvio.shared.model.AddonStreams
import com.nuvio.shared.model.Stream
import com.nuvio.shared.model.StreamsUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Port of StreamRepositoryImpl.fetchStreamsFromAllSources (add-on part): every add-on that
 * declares the stream resource for this type/id is queried in parallel and results are emitted
 * as they arrive, kept in add-on priority order.
 */
class StreamRepository(private val client: StremioClient) {

    fun streamAddons(addons: List<Addon>, type: String, videoId: String): List<Addon> =
        addons.filter { it.enabled && it.supportsResource("stream", type, videoId) }

    fun streams(
        addons: List<Addon>,
        type: String,
        videoId: String,
        embedded: List<Stream> = emptyList()
    ): Flow<StreamsUpdate> = channelFlow {
        val targets = streamAddons(addons, type, videoId)
        val results = arrayOfNulls<AddonStreams>(targets.size)
        val failed = mutableListOf<String>()
        val lock = Mutex()
        var remaining = targets.size
        val embeddedGroup = embedded.takeIf { it.isNotEmpty() }?.let {
            AddonStreams(addonName = "Embedded Streams", addonLogo = null, streams = it)
        }

        fun snapshot(complete: Boolean) = StreamsUpdate(
            groups = listOfNotNull(embeddedGroup) + results.filterNotNull().filter { it.streams.isNotEmpty() },
            isComplete = complete,
            failedAddons = failed.toList()
        )

        send(snapshot(targets.isEmpty()))
        targets.forEachIndexed { index, addon ->
            launch {
                val group = try {
                    AddonStreams(addon.displayName, addon.logo, client.fetchStreams(addon, type, videoId))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                lock.withLock {
                    if (group == null) failed += addon.displayName else results[index] = group
                    remaining -= 1
                    send(snapshot(remaining == 0))
                }
            }
        }
    }
}
