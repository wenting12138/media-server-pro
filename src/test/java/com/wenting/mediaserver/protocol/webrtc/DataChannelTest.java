package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.protocol.webrtc.core.sctp.*;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

import static com.wenting.mediaserver.protocol.webrtc.core.sctp.SctpConstants.*;
import static org.junit.Assert.*;

/**
 * DataChannel protocol tests (RFC 8831, RFC 8832).
 */
public class DataChannelTest {

    @Test
    public void testInitialState() {
        DataChannel dc = new DataChannel(null, 0, "test", DATA_CHANNEL_RELIABLE, 0);
        assertEquals(DataChannel.State.CONNECTING, dc.getState());
        assertEquals("test", dc.getLabel());
        assertEquals(0, dc.getId());
        assertFalse(dc.isUnordered());
    }

    @Test
    public void testReliableUnordered() {
        DataChannel dc = new DataChannel(null, 1, "u", DATA_CHANNEL_RELIABLE_UNORDERED, 0);
        assertTrue(dc.isUnordered());
    }

    @Test
    public void testPartialReliableRexmit() {
        DataChannel dc = new DataChannel(null, 2, "pr", DATA_CHANNEL_PARTIAL_RELIABLE_REXMIT, 3);
        assertTrue(dc.isUnordered());
    }

    @Test
    public void testPartialReliableTimed() {
        DataChannel dc = new DataChannel(null, 3, "pt", DATA_CHANNEL_PARTIAL_RELIABLE_TIMED, 5000);
        assertTrue(dc.isUnordered());
    }

    @Test
    public void testHandleAckTransitionsToOpen() {
        DataChannel dc = new DataChannel(null, 0, "test", DATA_CHANNEL_RELIABLE, 0);
        dc.handleAck();
        assertEquals(DataChannel.State.OPEN, dc.getState());
    }

    @Test
    public void testCloseTransitionsToClosed() {
        DataChannel dc = new DataChannel(null, 0, "test", DATA_CHANNEL_RELIABLE, 0);
        dc.handleAck();
        assertEquals(DataChannel.State.OPEN, dc.getState());
        dc.close();
        assertEquals(DataChannel.State.CLOSED, dc.getState());
    }

    @Test
    public void testParseOpenMessage() {
        String label = "test-channel";
        byte[] labelBytes = label.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(12 + labelBytes.length);
        buf.put((byte) DATA_CHANNEL_OPEN);
        buf.put((byte) DATA_CHANNEL_RELIABLE);
        buf.putShort((short) 0); // priority
        buf.putInt(0); // reliability parameter
        buf.putShort((short) labelBytes.length);
        buf.putShort((short) 0); // protocol length
        buf.put(labelBytes);

        byte[] openMsg = buf.array();
        DataChannel dc = DataChannel.parseOpenMessage(null, openMsg, 5);
        assertNotNull(dc);
        assertEquals("test-channel", dc.getLabel());
        assertEquals(5, dc.getId());
        assertFalse(dc.isUnordered());
    }

    @Test
    public void testParseOpenMessageUnordered() {
        String label = "unordered";
        byte[] labelBytes = label.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(12 + labelBytes.length);
        buf.put((byte) DATA_CHANNEL_OPEN);
        buf.put((byte) DATA_CHANNEL_RELIABLE_UNORDERED);
        buf.putShort((short) 0);
        buf.putInt(0);
        buf.putShort((short) labelBytes.length);
        buf.putShort((short) 0);
        buf.put(labelBytes);

        DataChannel dc = DataChannel.parseOpenMessage(null, buf.array(), 3);
        assertNotNull(dc);
        assertEquals("unordered", dc.getLabel());
        assertTrue(dc.isUnordered());
    }

    @Test
    public void testParseOpenMessageWithEmptyLabel() {
        ByteBuffer buf = ByteBuffer.allocate(12);
        buf.put((byte) DATA_CHANNEL_OPEN);
        buf.put((byte) DATA_CHANNEL_RELIABLE);
        buf.putShort((short) 0);
        buf.putInt(0);
        buf.putShort((short) 0); // empty label
        buf.putShort((short) 0);

        DataChannel dc = DataChannel.parseOpenMessage(null, buf.array(), 7);
        assertNotNull(dc);
        assertEquals("", dc.getLabel());
        assertEquals(7, dc.getId());
    }

    @Test
    public void testParseOpenMessageReturnsNullForNonOpenType() {
        ByteBuffer buf = ByteBuffer.allocate(12);
        buf.put((byte) DATA_CHANNEL_ACK); // Wrong type
        buf.put((byte) DATA_CHANNEL_RELIABLE);
        buf.putShort((short) 0);
        buf.putInt(0);
        buf.putShort((short) 0);
        buf.putShort((short) 0);

        DataChannel dc = DataChannel.parseOpenMessage(null, buf.array(), 0);
        assertNull(dc);
    }

    @Test
    public void testStateChangeCallback() {
        DataChannel dc = new DataChannel(null, 0, "test", DATA_CHANNEL_RELIABLE, 0);
        AtomicReference<DataChannel.State> captured = new AtomicReference<>();
        dc.setStateHandler(captured::set);

        dc.handleAck();
        assertEquals(DataChannel.State.OPEN, captured.get());

        dc.close();
        assertEquals(DataChannel.State.CLOSED, captured.get());
    }
}
