package com.wenting.mediaserver.core.remux.rtp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AvcDecoderConfigurationRecordParser {

    public AvcDecoderConfigurationRecord parse(byte[] payload) {
        if (payload == null || payload.length < 7) {
            return null;
        }
        int index = 0;
        index++; // configurationVersion
        int avcProfileIndication = payload[index++] & 0xFF;
        int profileCompatibility = payload[index++] & 0xFF;
        int avcLevelIndication = payload[index++] & 0xFF;
        int nalLengthSize = (payload[index++] & 0x03) + 1;
        int numOfSps = payload[index++] & 0x1F;
        List<byte[]> spsList = new ArrayList<byte[]>();
        for (int i = 0; i < numOfSps; i++) {
            if (index + 2 > payload.length) {
                return null;
            }
            int length = readUnsignedShort(payload, index);
            index += 2;
            if (index + length > payload.length) {
                return null;
            }
            spsList.add(copy(payload, index, length));
            index += length;
        }
        if (index >= payload.length) {
            return new AvcDecoderConfigurationRecord(
                    nalLengthSize,
                    String.format("%02X%02X%02X", avcProfileIndication, profileCompatibility, avcLevelIndication),
                    spsList,
                    Collections.<byte[]>emptyList()
            );
        }
        int numOfPps = payload[index++] & 0xFF;
        List<byte[]> ppsList = new ArrayList<byte[]>();
        for (int i = 0; i < numOfPps; i++) {
            if (index + 2 > payload.length) {
                return null;
            }
            int length = readUnsignedShort(payload, index);
            index += 2;
            if (index + length > payload.length) {
                return null;
            }
            ppsList.add(copy(payload, index, length));
            index += length;
        }
        return new AvcDecoderConfigurationRecord(
                nalLengthSize,
                String.format("%02X%02X%02X", avcProfileIndication, profileCompatibility, avcLevelIndication),
                spsList,
                ppsList
        );
    }

    private static int readUnsignedShort(byte[] payload, int offset) {
        return ((payload[offset] & 0xFF) << 8) | (payload[offset + 1] & 0xFF);
    }

    private static byte[] copy(byte[] payload, int offset, int length) {
        byte[] copy = new byte[length];
        System.arraycopy(payload, offset, copy, 0, length);
        return copy;
    }
}
