package com.wenting.mediaserver.protocol.rtsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtpPortAllocatorTest {

    @Test
    void shouldAllocateEvenOddPortPairsSequentially() {
        RtpPortAllocator allocator = new RtpPortAllocator(20000, 20005);

        RtpPortAllocation first = allocator.allocate();
        RtpPortAllocation second = allocator.allocate();

        assertEquals(20000, first.rtpPort());
        assertEquals(20001, first.rtcpPort());
        assertEquals(20002, second.rtpPort());
        assertEquals(20003, second.rtcpPort());
        assertTrue(allocator.isAllocated(20000));
        assertTrue(allocator.isAllocated(20002));
    }

    @Test
    void shouldReuseReleasedPair() {
        RtpPortAllocator allocator = new RtpPortAllocator(20000, 20003);

        RtpPortAllocation first = allocator.allocate();
        allocator.release(first);
        RtpPortAllocation reused = allocator.allocate();

        assertEquals(20000, reused.rtpPort());
        assertFalse(allocator.isAllocated(20002));
    }

    @Test
    void shouldThrowWhenNoPortsRemain() {
        RtpPortAllocator allocator = new RtpPortAllocator(20000, 20003);

        allocator.allocate();
        allocator.allocate();

        assertThrows(IllegalStateException.class, allocator::allocate);
    }
}
