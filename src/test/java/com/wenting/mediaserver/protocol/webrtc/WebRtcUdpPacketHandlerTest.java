package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.protocol.webrtc.ice.IceAgent;
import com.wenting.mediaserver.protocol.webrtc.stun.StunMessage;
import com.wenting.mediaserver.protocol.webrtc.stun.StunMessageCodec;
import com.wenting.mediaserver.protocol.webrtc.stun.StunMessageType;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class WebRtcUdpPacketHandlerTest {

    @Test
    void shouldReplyWithBindingSuccessForKnownLocalUfrag() {
        WebRtcSessionManager sessionManager = new WebRtcSessionManager();
        IceAgent iceAgent = new IceAgent("localufrag", "localpwd");
        iceAgent.addHostCandidate("127.0.0.1", 18081);
        sessionManager.register(new WebRtcPeerSession(
                "peer-1",
                new StreamKey(StreamProtocol.RTMP, "live", "cam01"),
                "offer",
                "answer",
                "localufrag",
                "localpwd",
                "AA:BB",
                iceAgent,
                System.currentTimeMillis()
        ));
        EmbeddedChannel channel = new EmbeddedChannel(new WebRtcUdpPacketHandler(sessionManager));

        InetSocketAddress localAddress = new InetSocketAddress("127.0.0.1", 18081);
        InetSocketAddress remoteAddress = new InetSocketAddress("10.0.0.20", 50000);
        channel.writeInbound(new DatagramPacket(Unpooled.wrappedBuffer(bindingRequest("remote:localufrag")), localAddress, remoteAddress));

        DatagramPacket response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(remoteAddress, response.recipient());
        StunMessage message = new StunMessageCodec().decode(io.netty.buffer.ByteBufUtil.getBytes(response.content()));
        assertNotNull(message);
        assertEquals(StunMessageType.BINDING_SUCCESS_RESPONSE, message.type());
        response.release();
        channel.finishAndReleaseAll();
    }

    private static byte[] bindingRequest(String username) {
        byte[] transactionId = new byte[] {0x63, 0x41, 0x11, 0x22, 0x01, 0x02, 0x03, 0x04, 0x33, 0x44, 0x55, 0x66};
        java.io.ByteArrayOutputStream attributes = new java.io.ByteArrayOutputStream();
        writeAttribute(attributes, 0x0006, username.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] attributeBytes = attributes.toByteArray();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        writeUnsignedShort(out, 0x0001);
        writeUnsignedShort(out, attributeBytes.length);
        out.write(0x21);
        out.write(0x12);
        out.write(0xA4);
        out.write(0x42);
        out.write(transactionId, 0, transactionId.length);
        out.write(attributeBytes, 0, attributeBytes.length);
        return out.toByteArray();
    }

    private static void writeAttribute(java.io.ByteArrayOutputStream out, int type, byte[] value) {
        byte[] safeValue = value == null ? new byte[0] : value;
        writeUnsignedShort(out, type);
        writeUnsignedShort(out, safeValue.length);
        out.write(safeValue, 0, safeValue.length);
        int padding = ((safeValue.length + 3) & ~0x03) - safeValue.length;
        for (int i = 0; i < padding; i++) {
            out.write(0x00);
        }
    }

    private static void writeUnsignedShort(java.io.ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }
}
