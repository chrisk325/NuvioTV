package com.nuvio.shared

import com.nuvio.shared.util.encodeUrlComponent
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Android app encodes with URLEncoder; the shared encoder must produce identical URLs. */
class JvmParityTest {
    @Test
    fun encoderMatchesUrlEncoder() {
        val samples = listOf("tt0944947:1:1", "kitsu:12", "the office", "a+b&c=d", "ünïcødé/?#", "日本", "~!'()")
        samples.forEach {
            assertEquals(URLEncoder.encode(it, "UTF-8").replace("+", "%20"), encodeUrlComponent(it), it)
        }
    }
}
