package com.nuvio.tv.reshaped.livetv

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Keeps the channel being watched on one connection, whichever player shows it.
 *
 * The list's preview and the full screen player are two players. Going from one to the other used
 * to close the channel's connection and open it again: a new connection, a wait for the stream's
 * next key frame and, on accounts that allow one connection, often a refusal first, because the
 * server still counted the connection just closed. Phones have no preview and never paid for this.
 *
 * Here a channel's connection belongs to a session instead of a player. A thread reads it into a
 * ring of the last few megabytes, and players read from the ring. A player that leaves can ask
 * for the session to stay a moment ([hold]); the player that opens the same channel next joins it,
 * starting a few seconds back in the ring so it has a key frame at once. [prewarm] opens the
 * connection as OK is pressed, while the full screen player is still being built.
 *
 * Only one session exists at a time, as IPTV accounts expect: opening another channel closes it.
 * Streams with a known length (files) are not shared; they are read straight from their connection.
 */
internal class LiveTvStreamHub(
    private val capacityBytes: () -> Int,
    private val nanoTime: () -> Long = System::nanoTime,
    private val log: (String) -> Unit = {},
) {
    /** A connection's bytes. */
    interface Upstream {
        /** Blocks for data; -1 at the end. Called by one thread at a time. */
        @Throws(IOException::class)
        fun read(buffer: ByteArray, offset: Int, length: Int): Int

        /** Stops a read in progress. Safe from any thread. */
        fun cancel()

        /** Frees the connection. Called once, after the last read. */
        fun release()
    }

    /**
     * A connection just opened. [length] is -1 when the server gave none (a live stream).
     * [joinable] false keeps players from joining part way (a stream framed from its first byte).
     */
    class Opened(val upstream: Upstream, val length: Long, val joinable: Boolean, val info: Any?)

    /** What a player reads. */
    interface Stream {
        /** -1 for a live stream. */
        val length: Long
        val info: Any?

        @Throws(IOException::class)
        fun read(buffer: ByteArray, offset: Int, length: Int): Int

        fun close()
    }

    // Lock order: hubLock, then a session's lock. Never the other way round.
    private val hubLock = Any()
    private var current: Session? = null
    private var opening: Opening? = null
    private var spareRing: ByteArray? = null
    private var holdGraceMs = 0L
    private var holdArmed = false
    private var holdArmedUntilNanos = 0L

    private val scheduler = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "LiveTvStreamHub").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        setKeepAliveTime(30, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
    }

    /**
     * The stream for [key]: the session already open for it when there is one to join, else a new
     * connection from [opener]. An error from [opener] is thrown as it is.
     */
    @Throws(IOException::class)
    fun open(key: String, opener: () -> Opened): Stream {
        while (true) {
            var pending: Opening? = null
            var mine: Opening? = null
            var old: Session? = null
            synchronized(hubLock) {
                val session = current
                if (session != null && session.key == key) {
                    session.join()?.let {
                        log("joined the open connection")
                        return it
                    }
                }
                val other = opening
                if (other != null && other.key == key) {
                    pending = other
                } else {
                    old = current
                    current = null
                    mine = Opening(key).also { opening = it }
                }
            }
            val wait = pending
            if (wait != null) {
                // The same channel is being opened (OK was just pressed): one connection, not two.
                wait.await()
                continue
            }
            old?.close()
            return openFresh(key, mine!!, opener)
        }
    }

    private fun openFresh(key: String, mine: Opening, opener: () -> Opened): Stream {
        val opened = try {
            opener()
        } catch (error: Throwable) {
            finish(mine)
            throw error
        }
        val ring = if (opened.length == -1L) takeRing() else null
        if (ring == null) {
            finish(mine)
            return Direct(opened)
        }
        log("opened a connection")
        val session = Session(key, opened, ring)
        val reader = session.firstReader()
        synchronized(hubLock) {
            // Another channel was asked for meanwhile: this one still plays, but nobody joins it.
            if (opening === mine) current = session
        }
        finish(mine)
        session.start()
        return reader
    }

    /**
     * Opens [key] before its player asks, and keeps it for [graceMs]. Blocks while connecting.
     * Returns false when the stream turned out to have a length, so there is nothing to share.
     */
    fun prewarm(key: String, graceMs: Long, opener: () -> Opened): Boolean {
        val mine: Opening
        val old: Session?
        synchronized(hubLock) {
            val session = current
            if (session != null && session.key == key && session.keepWaiting(graceMs)) return true
            if (opening?.key == key) return true
            old = current
            current = null
            mine = Opening(key).also { opening = it }
        }
        old?.close()
        val opened = try {
            opener()
        } catch (error: Exception) {
            // The player opens it itself, and shows or retries the error as it always did.
            finish(mine)
            return true
        }
        val ring = if (opened.length == -1L) takeRing() else null
        if (ring == null) {
            finish(mine)
            opened.upstream.cancel()
            opened.upstream.release()
            return opened.length == -1L
        }
        val session = Session(key, opened, ring)
        val kept = synchronized(hubLock) {
            (opening === mine).also { if (it) current = session }
        }
        finish(mine)
        if (kept) {
            log("opened a connection ahead of its player")
            session.start()
            session.keepWaiting(graceMs)
        } else {
            session.close()
        }
        return true
    }

    /**
     * The player about to let go of the current channel hands it on: when it leaves within
     * [armedMs], the session stays open [graceMs] for the next player. Without this a session
     * closes with its last player, so moving on to another channel frees the connection at once.
     */
    fun hold(graceMs: Long, armedMs: Long = HOLD_ARMED_MS) {
        synchronized(hubLock) {
            holdGraceMs = graceMs
            holdArmed = true
            holdArmedUntilNanos = nanoTime() + armedMs * 1_000_000L
            current?.waitLonger(graceMs)
        }
    }

    /** Whether a session for [key] is open. */
    fun isOpen(key: String): Boolean = synchronized(hubLock) { current?.key == key }

    private fun finish(mine: Opening) {
        synchronized(hubLock) {
            if (opening === mine) opening = null
        }
        mine.finish()
    }

    private fun readerLeft(session: Session) {
        var closeNow = false
        synchronized(hubLock) {
            var graceMs = 0L
            if (current === session) {
                if (holdArmed && nanoTime() - holdArmedUntilNanos < 0) graceMs = holdGraceMs
                holdArmed = false
            }
            if (!session.waitWhenUnread(graceMs)) {
                if (current === session) current = null
                closeNow = true
            }
        }
        if (closeNow) session.close()
    }

    /** Closes [session] when nobody reads it: its wait for the next player ran out, or it ended. */
    private fun closeIfUnread(session: Session) {
        synchronized(hubLock) {
            if (!session.unread()) return
            if (current === session) current = null
        }
        session.close()
    }

    private fun takeRing(): ByteArray? {
        val size = capacityBytes()
        synchronized(hubLock) {
            val spare = spareRing
            if (spare != null) {
                spareRing = null
                if (spare.size == size) return spare
            }
        }
        // A TV too short of memory for the ring plays the stream straight from its connection.
        return try {
            ByteArray(size)
        } catch (error: OutOfMemoryError) {
            null
        }
    }

    private fun recycle(ring: ByteArray) {
        synchronized(hubLock) { if (spareRing == null) spareRing = ring }
        scheduler.schedule(
            { synchronized(hubLock) { if (current == null && opening == null) spareRing = null } },
            SPARE_RING_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private class Opening(val key: String) {
        private val done = CountDownLatch(1)

        fun finish() = done.countDown()

        fun await() {
            try {
                done.await()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException()
            }
        }
    }

    /** A stream with a length: read from its connection by its one player, as before. */
    private class Direct(private val opened: Opened) : Stream {
        override val length: Long get() = opened.length
        override val info: Any? get() = opened.info
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = opened.upstream.read(buffer, offset, length)
        override fun close() = opened.upstream.release()
    }

    private inner class Session(val key: String, private val opened: Opened, private val ring: ByteArray) {
        private val lock = ReentrantLock()
        private val changed = lock.newCondition()
        private val readers = ArrayList<Reader>(2)

        /** One read from the connection. */
        private val chunkSize = minOf(CHUNK, ring.size / 4)

        /** Bytes received so far; the ring holds the last [ring] size of them. */
        private var head = 0L
        private var ended = false
        private var failure: IOException? = null
        private var closed = false
        private var started = false
        private var pumpDone = false
        private var waitTask: ScheduledFuture<*>? = null

        /**
         * Where MPEG-TS packets start, as an offset from 0 to 187: [PHASE_UNKNOWN] until enough of
         * the stream has arrived, [PHASE_NONE] when it is not a transport stream.
         */
        private var packetPhase = PHASE_UNKNOWN

        // When each stretch of the stream arrived, so a joining player starts a set time back.
        private val markOffsets = LongArray(MARKS)
        private val markTimes = LongArray(MARKS)
        private var markCount = 0L

        private val over: Boolean get() = closed || ended || failure != null

        fun firstReader(): Stream = lock.withLock { Reader(0L).also(readers::add) }

        fun start() {
            lock.withLock {
                if (closed || started) return
                started = true
            }
            Thread(::pump, "LiveTvStream").apply { isDaemon = true }.start()
        }

        /** A reader for a player joining this session, or null when it can't be joined. */
        fun join(): Stream? = lock.withLock {
            if (over) return null
            // What the read in progress may still overwrite is not handed out.
            val floor = (head - ring.size + chunkSize).coerceAtLeast(0L)
            val start = when {
                opened.joinable && packetPhase >= 0 ->
                    packetStart(maxOf(arrivedSince(nanoTime() - BACKLOG_NS), floor)) ?: 0L.takeIf { floor == 0L }
                // Any other format needs its first bytes: joined only while the ring still has them.
                floor == 0L -> 0L
                else -> null
            } ?: return null
            waitTask?.cancel(false)
            waitTask = null
            Reader(start).also(readers::add)
        }

        fun unread(): Boolean = lock.withLock { readers.isEmpty() && !closed }

        /**
         * With no reader left: stays open [graceMs] for the next player and returns true, or
         * returns false when it should close now (no grace, or the stream is over). Also true
         * while a reader remains.
         */
        fun waitWhenUnread(graceMs: Long): Boolean = lock.withLock {
            if (closed || readers.isNotEmpty()) return true
            if (graceMs <= 0L || over) return false
            waitFor(graceMs)
            true
        }

        /** Stays open [graceMs] from now when no player reads it. False when the stream is over. */
        fun keepWaiting(graceMs: Long): Boolean = lock.withLock {
            if (over) return false
            if (readers.isEmpty()) waitFor(graceMs)
            true
        }

        /** A session already waiting for its next player waits [graceMs] from now. */
        fun waitLonger(graceMs: Long) = lock.withLock {
            if (!over && readers.isEmpty() && waitTask != null) waitFor(graceMs)
        }

        private fun waitFor(graceMs: Long) {
            waitTask?.cancel(false)
            waitTask = scheduler.schedule({ closeIfUnread(this) }, graceMs, TimeUnit.MILLISECONDS)
        }

        fun close() {
            val neverRead: Boolean
            val recycleNow: Boolean
            lock.withLock {
                if (closed) return
                closed = true
                waitTask?.cancel(false)
                waitTask = null
                neverRead = !started
                recycleNow = pumpDone || neverRead
                changed.signalAll()
            }
            opened.upstream.cancel()
            if (neverRead) opened.upstream.release()
            if (recycleNow) recycle(ring)
        }

        private fun pump() {
            val chunk = ByteArray(chunkSize)
            try {
                while (true) {
                    val room = lock.withLock {
                        // A player that reads slowly (paused, buffer full) slows the connection
                        // down, as it would its own; with no player the ring keeps the newest.
                        while (!closed && readers.isNotEmpty() && head - slowest() >= ring.size) changed.await()
                        if (closed) return
                        if (readers.isEmpty()) chunkSize else minOf(chunkSize.toLong(), ring.size - (head - slowest())).toInt()
                    }
                    val read = opened.upstream.read(chunk, 0, room)
                    lock.withLock {
                        if (closed) return
                        if (read < 0) ended = true else if (read > 0) write(chunk, read)
                        changed.signalAll()
                        if (ended) return
                    }
                }
            } catch (error: IOException) {
                fail(error)
            } catch (error: InterruptedException) {
                fail(InterruptedIOException())
            } catch (error: RuntimeException) {
                // A connection cancelled from another thread can fail in odd ways.
                fail(IOException(error))
            } finally {
                runCatching { opened.upstream.release() }
                val recycleNow = lock.withLock {
                    pumpDone = true
                    closed
                }
                // Ended or failed with players reading: they are told as they read on.
                if (recycleNow) recycle(ring) else closeIfUnread(this)
            }
        }

        private fun fail(error: IOException) {
            lock.withLock {
                if (!closed && failure == null) failure = error
                changed.signalAll()
            }
        }

        private fun slowest(): Long {
            var min = Long.MAX_VALUE
            for (reader in readers) if (reader.position < min) min = reader.position
            return min
        }

        private fun write(chunk: ByteArray, count: Int) {
            val now = nanoTime()
            if (markCount == 0L || now - markTimes[((markCount - 1) % MARKS).toInt()] >= MARK_NS) {
                val slot = (markCount % MARKS).toInt()
                markOffsets[slot] = head
                markTimes[slot] = now
                markCount++
            }
            val at = (head % ring.size).toInt()
            val first = minOf(count, ring.size - at)
            System.arraycopy(chunk, 0, ring, at, first)
            if (first < count) System.arraycopy(chunk, first, ring, 0, count - first)
            head += count
            if (packetPhase == PHASE_UNKNOWN && head >= TS_PACKET + SYNC_SPAN) {
                packetPhase = if (head > ring.size) PHASE_NONE else (0 until TS_PACKET.toInt()).firstOrNull { syncAt(it.toLong()) } ?: PHASE_NONE
            }
        }

        /** Where the bytes that arrived at or after [sinceNanos] start; [head] when none did. */
        private fun arrivedSince(sinceNanos: Long): Long {
            var start = head
            var index = markCount - 1
            val oldest = (markCount - MARKS).coerceAtLeast(0L)
            while (index >= oldest) {
                val slot = (index % MARKS).toInt()
                if (markTimes[slot] - sinceNanos < 0) break
                start = markOffsets[slot]
                index--
            }
            return start
        }

        /**
         * The first transport stream packet at or after [from], so a joining player starts on a
         * packet. Null when the stream is no longer in step there (the server spliced it).
         */
        private fun packetStart(from: Long): Long? {
            var start = from
            val past = Math.floorMod(start - packetPhase, TS_PACKET)
            if (past != 0L) start += TS_PACKET - past
            // Nothing that recent yet: the player starts with what comes next.
            if (start >= head) return start
            // Too little after it to check: packets have been in step since the first byte.
            if (start + SYNC_SPAN > head) return start
            if (syncAt(start)) return start
            for (shift in 1 until TS_PACKET) {
                val candidate = start + shift
                if (candidate + SYNC_SPAN > head) break
                if (syncAt(candidate)) return candidate
            }
            return null
        }

        private fun syncAt(offset: Long): Boolean {
            for (packet in 0 until SYNC_PACKETS) {
                if (ring[((offset + packet * TS_PACKET) % ring.size).toInt()] != TS_SYNC) return false
            }
            return true
        }

        private inner class Reader(var position: Long) : Stream {
            private var left = false
            override val length: Long get() = -1L
            override val info: Any? get() = opened.info

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                lock.withLock {
                    while (true) {
                        if (left) throw IOException("Live TV stream reader closed")
                        // Another channel took the connection.
                        if (closed) throw IOException("Live TV stream closed")
                        val available = head - position
                        if (available > 0) {
                            val count = minOf(length.toLong(), available).toInt()
                            val at = (position % ring.size).toInt()
                            val first = minOf(count, ring.size - at)
                            System.arraycopy(ring, at, buffer, offset, first)
                            if (first < count) System.arraycopy(ring, 0, buffer, offset + first, count - first)
                            position += count
                            changed.signalAll()
                            return count
                        }
                        // The connection's own error, so the player sees what it always saw.
                        failure?.let { throw it }
                        if (ended) return -1
                        try {
                            changed.await()
                        } catch (interrupted: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw InterruptedIOException()
                        }
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                return -1
            }

            override fun close() {
                lock.withLock {
                    if (left) return
                    left = true
                    readers.remove(this)
                    changed.signalAll()
                }
                readerLeft(this@Session)
            }
        }
    }

    companion object {
        /** How much of the stream's recent past a joining player starts with: a key frame or two. */
        private const val BACKLOG_NS = 4_000L * 1_000_000L

        /** How long after [hold] a player has to leave for its session to be kept. */
        const val HOLD_ARMED_MS = 2_000L

        /** The most one read from the connection takes. */
        private const val CHUNK = 32 * 1024

        private const val MARKS = 256
        private const val MARK_NS = 40L * 1_000_000L
        private const val TS_PACKET = 188L
        private const val TS_SYNC: Byte = 0x47
        private const val SYNC_PACKETS = 5
        private const val SYNC_SPAN = TS_PACKET * (SYNC_PACKETS - 1) + 1
        private const val PHASE_UNKNOWN = -1
        private const val PHASE_NONE = -2

        /** An unused ring is given back to the system after this long. */
        private const val SPARE_RING_MS = 60_000L
    }
}
