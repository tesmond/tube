package com.tube.tv.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class ResumeStoreTest {
    @Test fun `parse round trips and skips junk`() {
        val m = ResumeStore.parse("a=1000;b=2000;junk;=5;c=x;d=9")
        assertEquals(listOf("a", "b", "d"), m.keys.toList())
        assertEquals(2000L, m["b"])
    }
}
