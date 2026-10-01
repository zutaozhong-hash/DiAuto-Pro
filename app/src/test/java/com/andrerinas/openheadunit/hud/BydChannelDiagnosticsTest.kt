package com.andrerinas.openheadunit.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BydChannelDiagnosticsTest {

    private fun check(id: String, ok: Boolean?, detail: String = "value") = BydGateCheck(id, ok, detail)

    private fun group(id: String, vararg checks: BydGateCheck) = BydGateGroup(id, checks.toList())

    @Test
    fun `a group passes unless one of its checks actually failed`() {
        assertTrue(group("g", check("a", true)).ok)
        // An informational line carries context, not a verdict, so it must not fail the group.
        assertTrue(group("g", check("a", true), check("b", null)).ok)
        assertFalse(group("g", check("a", true), check("b", false)).ok)
    }

    @Test
    fun `the formatter gives every group a title and every check its own marked line`() {
        val text = BydReportFormatter.format(
            listOf(
                group("first", check("one", true, "1"), check("two", false, "2")),
                group("second", check("three", null, "3")),
            ),
            title = { "T:$it" },
            label = { "L:$it" },
        )

        val expected = listOf(
            "T:first",
            "${BydReportFormatter.PASS} L:one: 1",
            "${BydReportFormatter.FAIL} L:two: 2",
            "",
            "T:second",
            "${BydReportFormatter.INFO} L:three: 3",
        ).joinToString("\n")

        assertEquals(expected, text)
    }

    @Test
    fun `the info marker is the only one that does not mean success or failure`() {
        assertEquals(BydReportFormatter.PASS, BydReportFormatter.mark(true))
        assertEquals(BydReportFormatter.FAIL, BydReportFormatter.mark(false))
        assertEquals(BydReportFormatter.INFO, BydReportFormatter.mark(null))
    }

    @Test
    fun `the standalone receiver wins outright when it passes, exactly like the runtime picks it`() {
        val channels = BydChannelDiagnostics.activeChannels(
            listOf(
                group(BydChannelDiagnostics.GROUP_FIRMWARE, check("sdk", true)),
                group(BydChannelDiagnostics.GROUP_STANDALONE, check("clusterDebugInstalled", true)),
                group(BydChannelDiagnostics.GROUP_AMAP, check("amapService", true)),
                group(BydChannelDiagnostics.GROUP_SOME_IP, check("someIpService", true)),
            )
        )
        // The runtime returns as soon as this transport is usable and never reaches the others.
        assertEquals(listOf(BydChannelDiagnostics.GROUP_STANDALONE), channels)
    }

    @Test
    fun `the two fallback channels run side by side when the standalone receiver is unusable`() {
        val channels = BydChannelDiagnostics.activeChannels(
            listOf(
                group(BydChannelDiagnostics.GROUP_FIRMWARE, check("fingerprint", false)),
                group(BydChannelDiagnostics.GROUP_STANDALONE, check("clusterDebugInstalled", true)),
                group(BydChannelDiagnostics.GROUP_AMAP, check("amapService", true)),
                group(BydChannelDiagnostics.GROUP_SOME_IP, check("someIpService", true)),
            )
        )
        assertEquals(
            listOf(BydChannelDiagnostics.GROUP_AMAP, BydChannelDiagnostics.GROUP_SOME_IP),
            channels,
        )
    }

    @Test
    fun `no channel is reported when every gate fails`() {
        val channels = BydChannelDiagnostics.activeChannels(
            listOf(
                group(BydChannelDiagnostics.GROUP_FIRMWARE, check("sdk", false)),
                group(BydChannelDiagnostics.GROUP_STANDALONE, check("clusterDebugInstalled", false)),
                group(BydChannelDiagnostics.GROUP_AMAP, check("amapService", false)),
                group(BydChannelDiagnostics.GROUP_SOME_IP, check("someIpService", false)),
            )
        )
        assertTrue(channels.isEmpty())
    }

    @Test
    fun `a group that is missing from the report counts as unusable rather than as a pass`() {
        val channels = BydChannelDiagnostics.activeChannels(
            listOf(group(BydChannelDiagnostics.GROUP_AMAP, check("amapService", true)))
        )
        assertEquals(listOf(BydChannelDiagnostics.GROUP_AMAP), channels)
    }
}
