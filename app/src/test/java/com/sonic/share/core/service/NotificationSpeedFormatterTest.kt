package com.sonic.share.core.service

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationSpeedFormatterTest {

    @Test
    fun testSpeedFormatting() {
        assertEquals("500 KB/s", TransferNotificationHelper.formatSpeed(500 * 1024L))
        assertEquals("52.4 MB/s", TransferNotificationHelper.formatSpeed(52_400_000L))
        assertEquals("1.2 GB/s", TransferNotificationHelper.formatSpeed(1_200_000_000L))
    }

    @Test
    fun testEtaFormatting() {
        // 100 MB remaining at 50 MB/s = 2 seconds
        assertEquals("2s", TransferNotificationHelper.formatEta(100_000_000L, 50_000_000L))
        // 120 seconds = 2m 0s
        assertEquals("2m 0s", TransferNotificationHelper.formatEta(120_000_000L, 1_000_000L))
    }

    @Test
    fun testEtaEdgeCases() {
        assertEquals("--", TransferNotificationHelper.formatEta(100_000L, 0L))
        assertEquals("--", TransferNotificationHelper.formatEta(100_000L, -5L))
        assertEquals("0s", TransferNotificationHelper.formatEta(0L, 100_000L))
        assertEquals("0s", TransferNotificationHelper.formatEta(-10L, 100_000L))
    }
}
