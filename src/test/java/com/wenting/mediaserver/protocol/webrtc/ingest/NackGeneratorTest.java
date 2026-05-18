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

        assertTrue(generator.poll(1020L, 32).lostSequenceNumbers().isEmpty());
        assertEquals(Arrays.asList(1001, 1002), generator.poll(1040L, 32).lostSequenceNumbers());
    }

    @Test
    void shouldStopNackingRecoveredSequence() {
        NackGenerator generator = new NackGenerator(64, 30L, 40L, 4, 1200L);

        generator.onSequenceReceived(1000, 1000L);
        generator.onSequenceReceived(1003, 1005L);
        assertEquals(Arrays.asList(1001, 1002), generator.poll(1040L, 32).lostSequenceNumbers());

        generator.onSequenceReceived(1001, 1050L);

        List<Integer> next = generator.poll(1085L, 32).lostSequenceNumbers();
        assertEquals(Collections.singletonList(1002), next);
    }

    @Test
    void shouldRequestPliAfterRepeatedNackFailure() {
        NackGenerator generator = new NackGenerator(64, 30L, 40L, 2, 1200L);

        generator.onSequenceReceived(1000, 1000L);
        generator.onSequenceReceived(1002, 1005L);

        PollResult first = generator.poll(1040L, 32);
        assertEquals(Collections.singletonList(1001), first.lostSequenceNumbers());
        assertTrue(!first.requestKeyFrameRecovery());

        PollResult second = generator.poll(1085L, 32);
        assertEquals(Collections.singletonList(1001), second.lostSequenceNumbers());
        assertTrue(second.requestKeyFrameRecovery());
    }
}
