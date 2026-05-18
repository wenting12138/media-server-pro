package com.wenting.mediaserver.protocol.webrtc.ingest;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NackGeneratorTest {

    @Test
    void shouldDelayNackUntilGapAgesPastThreshold() {
        NackGenerator generator = new NackGenerator(64, 30L, 40L, 4, 1200L);

        generator.onSequenceReceived(1000, 1000L);
        generator.onSequenceReceived(1003, 1005L);

        assertTrue(generator.pollDueNacks(1020L, 32).isEmpty());
        assertEquals(Arrays.asList(1001, 1002), generator.pollDueNacks(1040L, 32));
    }

    @Test
    void shouldStopNackingRecoveredSequence() {
        NackGenerator generator = new NackGenerator(64, 30L, 40L, 4, 1200L);

        generator.onSequenceReceived(1000, 1000L);
        generator.onSequenceReceived(1003, 1005L);
        assertEquals(Arrays.asList(1001, 1002), generator.pollDueNacks(1040L, 32));

        generator.onSequenceReceived(1001, 1050L);

        List<Integer> next = generator.pollDueNacks(1085L, 32);
        assertEquals(Collections.singletonList(1002), next);
    }
}
