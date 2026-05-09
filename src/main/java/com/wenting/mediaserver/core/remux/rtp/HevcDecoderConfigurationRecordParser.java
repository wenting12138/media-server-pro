package com.wenting.mediaserver.core.remux.rtp;

import java.util.ArrayList;
import java.util.List;

public final class HevcDecoderConfigurationRecordParser {

    public HevcDecoderConfigurationRecord parse(byte[] payload) {
        if (payload == null || payload.length < 23) {
            return null;
        }
        int nalLengthSize = (payload[21] & 0x03) + 1;
        int index = 22;
        int numOfArrays = payload[index++] & 0xFF;
        List<byte[]> vpsList = new ArrayList<byte[]>();
        List<byte[]> spsList = new ArrayList<byte[]>();
        List<byte[]> ppsList = new ArrayList<byte[]>();
        for (int i = 0; i < numOfArrays; i++) {
            if (index + 3 > payload.length) {
                return null;
            }
            int nalType = payload[index++] & 0x3F;
            int numNalus = readUnsignedShort(payload, index);
            index += 2;
            for (int j = 0; j < numNalus; j++) {
                if (index + 2 > payload.length) {
                    return null;
                }
                int length = readUnsignedShort(payload, index);
                index += 2;
                if (index + length > payload.length) {
                    return null;
                }
                byte[] nal = copy(payload, index, length);
                index += length;
                if (nalType == 32) {
                    vpsList.add(nal);
                } else if (nalType == 33) {
                    spsList.add(nal);
                } else if (nalType == 34) {
                    ppsList.add(nal);
                }
            }
        }
        return new HevcDecoderConfigurationRecord(nalLengthSize, vpsList, spsList, ppsList);
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
