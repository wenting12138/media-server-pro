package com.wenting.mediaserver.protocol.webrtc.core.sctp;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static com.wenting.mediaserver.protocol.webrtc.core.sctp.SctpConstants.*;

/**
 * SCTP packet (RFC 4960 Section 3).
 *
 * Common header + chunk list.
 */
public class SctpPacket {

    private final int sourcePort;
    private final int destPort;
    private final long verificationTag;
    private final List<SctpChunk> chunks;

    public SctpPacket(int sourcePort, int destPort, long verificationTag, List<SctpChunk> chunks) {
        this.sourcePort = sourcePort;
        this.destPort = destPort;
        this.verificationTag = verificationTag;
        this.chunks = chunks;
    }

    public int getSourcePort() { return sourcePort; }
    public int getDestPort() { return destPort; }
    public long getVerificationTag() { return verificationTag; }
    public List<SctpChunk> getChunks() { return chunks; }

    /**
     * Encode packet to bytes, computing the CRC32c checksum.
     */
    public byte[] encode() {
        // Encode to a temporary buffer without checksum
        ByteBuffer tmp = ByteBuffer.allocate(65536);
        tmp.putShort((short) sourcePort);
        tmp.putShort((short) destPort);
        tmp.putInt((int) verificationTag);
        tmp.putInt(0); // placeholder for checksum
        for (SctpChunk chunk : chunks) {
            tmp.put(chunk.encode());
        }

        tmp.flip();
        byte[] packet = new byte[tmp.remaining()];
        tmp.get(packet);

        // Compute and insert CRC32c
        int crc = crc32c(packet, packet.length);
        packet[8] = (byte) (crc >> 24);
        packet[9] = (byte) (crc >> 16);
        packet[10] = (byte) (crc >> 8);
        packet[11] = (byte) crc;

        return packet;
    }

    /**
     * Decode an SCTP packet from raw bytes (assuming checksum has been validated).
     */
    public static SctpPacket decode(byte[] data) {
        if (data.length < HEADER_SIZE) {
            throw new IllegalArgumentException("SCTP packet too short: " + data.length);
        }

        ByteBuffer buf = ByteBuffer.wrap(data);
        int srcPort = buf.getShort() & 0xFFFF;
        int dstPort = buf.getShort() & 0xFFFF;
        long verTag = buf.getInt() & 0xFFFFFFFFL;
        buf.getInt(); // skip checksum

        List<SctpChunk> chunks = new ArrayList<>();
        while (buf.remaining() >= CHUNK_HEADER_SIZE) {
            SctpChunk chunk = SctpChunk.decode(data, buf.position());
            if (chunk == null) break;

            // Advance by the chunk length (padded to 4-byte boundary)
            int chunkLen = ((data[buf.position() + 2] & 0xFF) << 8)
                         | (data[buf.position() + 3] & 0xFF);
            buf.position(buf.position() + chunkLen);
            chunks.add(chunk);
        }

        return new SctpPacket(srcPort, dstPort, verTag, chunks);
    }

    // ---- CRC32c (Castagnoli) - SCTP uses this instead of CRC32 ----

    private static final int[] CRC32C_TABLE = buildCrc32cTable();

    private static int[] buildCrc32cTable() {
        int[] table = new int[256];
        for (int n = 0; n < 256; n++) {
            int c = n;
            for (int k = 0; k < 8; k++) {
                if ((c & 1) != 0) {
                    c = 0x82F63B78 ^ (c >>> 1);
                } else {
                    c = c >>> 1;
                }
            }
            table[n] = c;
        }
        return table;
    }

    static int crc32c(byte[] data, int length) {
        int crc = 0xFFFFFFFF;
        for (int i = 0; i < length; i++) {
            int index = (crc ^ data[i]) & 0xFF;
            crc = CRC32C_TABLE[index] ^ (crc >>> 8);
        }
        return crc ^ 0xFFFFFFFF;
    }

    @Override
    public String toString() {
        return "SctpPacket{" + sourcePort + "->" + destPort
            + " tag=" + verificationTag + " chunks=" + chunks.size() + "}";
    }
}
