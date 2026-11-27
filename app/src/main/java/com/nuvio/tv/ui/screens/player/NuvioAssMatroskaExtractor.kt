package com.nuvio.tv.ui.screens.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.EbmlProcessor
import androidx.media3.extractor.text.SubtitleParser
import com.nuvio.tv.core.player.SubtitleCharsetDetector
import com.nuvio.tv.core.player.dvmkv.MatroskaExtractor
import com.nuvio.tv.core.player.dvmkv.MatroskaExtractor.DolbyVisionSampleTransformer
import com.nuvio.tv.libass.media.AssHandler
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

@OptIn(UnstableApi::class)
internal class NuvioAssMatroskaExtractor(
    subtitleParserFactory: SubtitleParser.Factory,
    private val assHandler: AssHandler,
    flags: Int = 0,
    dolbyVisionSampleTransformer: DolbyVisionSampleTransformer? = null
) : MatroskaExtractor(
    subtitleParserFactory,
    flags,
    dolbyVisionSampleTransformer
) {

    private var currentAttachmentName: String? = null
    private var currentAttachmentMime: String? = null
    internal val subtitleSample: ParsableByteArray =
        subtitleSampleField.get(this) as ParsableByteArray

    override fun getElementType(id: Int): Int = when (id) {
        ID_ATTACHMENTS -> EbmlProcessor.ELEMENT_TYPE_MASTER
        ID_ATTACHED_FILE -> EbmlProcessor.ELEMENT_TYPE_MASTER
        ID_FILE_NAME -> EbmlProcessor.ELEMENT_TYPE_STRING
        ID_FILE_MIME_TYPE -> EbmlProcessor.ELEMENT_TYPE_STRING
        ID_FILE_DATA -> EbmlProcessor.ELEMENT_TYPE_BINARY
        else -> super.getElementType(id)
    }

    override fun isLevel1Element(id: Int): Boolean {
        return super.isLevel1Element(id) || id == ID_ATTACHMENTS
    }

    override fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) {
        when (id) {
            ID_EBML -> onEbmlStart(contentPosition, contentSize)
            ID_ATTACHED_FILE -> clearAttachment()
            else -> super.startMasterElement(id, contentPosition, contentSize)
        }
    }

    override fun endMasterElement(id: Int) {
        when (id) {
            ID_VIDEO -> onVideoEnd()
            ID_ATTACHED_FILE -> clearAttachment()
            else -> super.endMasterElement(id)
        }
    }

    override fun stringElement(id: Int, value: String) {
        when (id) {
            ID_FILE_NAME -> currentAttachmentName = value
            ID_FILE_MIME_TYPE -> currentAttachmentMime = value
            else -> super.stringElement(id, value)
        }
    }

    override fun binaryElement(id: Int, contentSize: Int, input: ExtractorInput) {
        when (id) {
            ID_FILE_DATA -> handleAttachmentData(contentSize, input)
            else -> super.binaryElement(id, contentSize, input)
        }
    }

    private fun onEbmlStart(contentPosition: Long, contentSize: Long) {
        // Always wrap the extractor output for overlay rendering (the new libass
        // module always uses the SurfaceView overlay pipeline).
        val currentExtractor = extractorOutput.get(this) as ExtractorOutput
        if (currentExtractor !is NuvioAssSubtitleExtractorOutput) {
            extractorOutput.set(
                this,
                NuvioAssSubtitleExtractorOutput(currentExtractor, assHandler, this)
            )
        }
        super.startMasterElement(ID_EBML, contentPosition, contentSize)
    }

    private fun onVideoEnd() {
        val track = getCurrentTrack(ID_VIDEO)
        assHandler.setVideoSize(track.width, track.height)
        super.endMasterElement(ID_VIDEO)
    }

    private fun handleAttachmentData(contentSize: Int, input: ExtractorInput) {
        val attachmentName = requireNotNull(currentAttachmentName)
        val attachmentMime = requireNotNull(currentAttachmentMime)

        if (attachmentMime in fontMimeTypes) {
            val data = ByteArray(contentSize)
            input.readFully(data, 0, contentSize)
            assHandler.addFont(attachmentName, data)
        } else {
            input.skipFully(contentSize)
        }
    }

    private fun clearAttachment() {
        currentAttachmentName = null
        currentAttachmentMime = null
    }

    private companion object {
        const val ID_EBML = 0x1A45DFA3
        const val ID_VIDEO = 0xE0
        const val ID_ATTACHMENTS = 0x1941A469
        const val ID_ATTACHED_FILE = 0x61A7
        const val ID_FILE_NAME = 0x466E
        const val ID_FILE_MIME_TYPE = 0x4660
        const val ID_FILE_DATA = 0x465C

        val fontMimeTypes = listOf(
            "font/ttf",
            "font/otf",
            "font/sfnt",
            "font/woff",
            "font/woff2",
            "application/font-sfnt",
            "application/font-woff",
            "application/x-truetype-font",
            "application/vnd.ms-opentype",
            "application/x-font-ttf"
        )

        val extractorOutput = MatroskaExtractor::class.java
            .getDeclaredField("extractorOutput")
            .apply { isAccessible = true }

        val subtitleSampleField = MatroskaExtractor::class.java
            .getDeclaredField("subtitleSample")
            .apply { isAccessible = true }
    }
}

@OptIn(UnstableApi::class)
private class NuvioAssSubtitleExtractorOutput(
    private val delegate: ExtractorOutput,
    private val assHandler: AssHandler,
    private val extractor: NuvioAssMatroskaExtractor
) : ExtractorOutput by delegate {
    override fun track(id: Int, type: Int): TrackOutput {
        return if (type == C.TRACK_TYPE_TEXT) {
            NuvioAssTrackOutput(delegate.track(id, type), assHandler, extractor)
        } else {
            delegate.track(id, type)
        }
    }
}

@OptIn(UnstableApi::class)
private class NuvioAssTrackOutput(
    private val delegate: TrackOutput,
    private val assHandler: AssHandler,
    private val extractor: NuvioAssMatroskaExtractor
) : TrackOutput by delegate {

    private var isAss = false
    private var trackId: String? = null

    override fun format(format: Format) {
        if (format.sampleMimeType == MimeTypes.TEXT_SSA || format.codecs == MimeTypes.TEXT_SSA) {
            isAss = true
            trackId = format.id
        }
        delegate.format(format)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?
    ) {
        if (isAss && timeUs.isValidTs) {
            val sample = extractor.subtitleSample
            val sampleLimit = sample.limit()
            val endIndex = findTokenIndex(sample.data, 1, sampleLimit)
            val lineIndex = findTokenIndex(sample.data, 2, sampleLimit)
            if (endIndex > 0 && lineIndex > endIndex) {
                val durationUs = parseSsaTimecodeUs(sample.data, endIndex, lineIndex - 1)
                if (durationUs.isValidTs) {
                    val dialogue = sample.data.dialoguePayload(
                        offset = lineIndex,
                        limit = sampleLimit
                    )

                    assHandler.readTrackDialogue(
                        trackId = trackId,
                        start = timeUs / 1000,
                        duration = durationUs / 1000,
                        data = dialogue,
                        offset = 0,
                        length = dialogue.size
                    )
                }
            }
        }
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }

    private fun findTokenIndex(array: ByteArray, tokenNumber: Int, limit: Int = array.size): Int {
        if (tokenNumber == 0) return 0
        var tokensFound = 0
        for (index in 0 until limit) {
            if (array[index] == COMMA && ++tokensFound == tokenNumber) {
                return index + 1
            }
        }
        return 0
    }

    private fun ByteArray.dialoguePayload(offset: Int, limit: Int): ByteArray {
        if (offset >= limit) return EMPTY_BYTE_ARRAY
        val raw = if (looksLikeZlib(offset, limit)) {
            maybeInflate(offset, size - offset) ?: copyOfRange(offset, limit.coerceIn(offset, size))
        } else {
            val boundedLimit = limit.coerceIn(offset, size)
            copyOfRange(offset, boundedLimit)
        }
        if (SubtitleCharsetDetector.isFastValidUtf8(raw, 0, raw.size)) return raw
        return SubtitleCharsetDetector.normalizeToUtf8(raw)
    }

    private fun ByteArray.looksLikeZlib(offset: Int, limit: Int): Boolean {
        if (limit - offset < 2) return false
        val cmf = this[offset].toInt() and 0xFF
        val flg = this[offset + 1].toInt() and 0xFF
        return cmf and 0x0F == 8 && ((cmf shl 8) + flg) % 31 == 0
    }

    private fun ByteArray.maybeInflate(offset: Int, length: Int): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(this, offset, length)
            val output = ByteArrayOutputStream(length * 4)
            val buffer = ByteArray(INFLATE_BUFFER_SIZE)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count > 0) {
                    output.write(buffer, 0, count)
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    break
                } else {
                    break
                }
            }
            val inflated = output.toByteArray()
            if (inflater.finished() && inflated.isNotEmpty()) inflated else null
        } catch (_: DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    private val Long.isValidTs: Boolean
        get() = this != C.TIME_UNSET

    private companion object {
        const val COMMA: Byte = 44
        const val INFLATE_BUFFER_SIZE = 4096
        val EMPTY_BYTE_ARRAY = ByteArray(0)
    }
}

/**
 * The duration Media3's Matroska extractor writes into an SSA sample ("H:MM:SS.CC"), read in place.
 *
 * This runs on the stream's loading thread once per subtitle line, and a heavy .ass track has
 * thousands of them; the regex it replaces allocated a string and a matcher every time, CPU that
 * the loader then did not spend draining the network. It reads what
 * `(?:(\d+):)?(\d+):(\d+)[:.](\d+)` matched on the trimmed text, in the ASCII digits Media3
 * writes: three or four runs of digits joined by ':', the last joiner ':' or '.', with the last
 * field in hundredths. Missing hours count as zero (the regex version threw on them), and a field
 * of more than nine digits is not a timecode.
 *
 * @return microseconds, or [C.TIME_UNSET] when bytes [from, to) are not such a timecode.
 */
internal fun parseSsaTimecodeUs(data: ByteArray, from: Int, to: Int): Long {
    var start = from.coerceAtLeast(0)
    var end = to.coerceAtMost(data.size)
    while (start < end && (data[start].toInt() and 0xFF) <= 0x20) start++
    while (end > start && (data[end - 1].toInt() and 0xFF) <= 0x20) end--

    val fields = LongArray(4)
    var fieldCount = 0
    var sawDot = false
    var index = start
    while (index < end) {
        if (fieldCount == fields.size) return C.TIME_UNSET
        var value = 0L
        var digits = 0
        while (index < end) {
            val digit = data[index] - ZERO
            if (digit < 0 || digit > 9) break
            if (++digits > MAX_TIMECODE_FIELD_DIGITS) return C.TIME_UNSET
            value = value * 10 + digit
            index++
        }
        if (digits == 0) return C.TIME_UNSET
        fields[fieldCount++] = value
        if (index == end) break
        // Only the separator before the last field may be '.', so no separator may follow one.
        if (sawDot) return C.TIME_UNSET
        when (data[index]) {
            COLON -> Unit
            DOT -> sawDot = true
            else -> return C.TIME_UNSET
        }
        index++
        // A separator must be followed by a field.
        if (index == end) return C.TIME_UNSET
    }
    if (fieldCount < 3) return C.TIME_UNSET
    val offset = fields.size - fieldCount
    val hours = if (offset == 0) fields[0] else 0L
    return hours * 60 * 60 * C.MICROS_PER_SECOND +
        fields[1 - offset] * 60 * C.MICROS_PER_SECOND +
        fields[2 - offset] * C.MICROS_PER_SECOND +
        fields[3 - offset] * 10_000
}

// Nine digits keep hours * 3.6e9 microseconds well inside a Long.
private const val MAX_TIMECODE_FIELD_DIGITS = 9
private const val COLON: Byte = 58
private const val DOT: Byte = 46
private const val ZERO: Byte = 48
