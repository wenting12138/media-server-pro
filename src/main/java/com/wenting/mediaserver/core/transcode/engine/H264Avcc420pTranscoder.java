package com.wenting.mediaserver.core.transcode.engine;

import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.transcode.canonical.CanonicalVideoFrame;
import com.wenting.mediaserver.core.transcode.canonical.H264CodecConfig;
import com.wenting.mediaserver.core.transcode.canonical.VideoPayloadFormat;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_free;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_unref;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_open2;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet;
import static org.bytedeco.ffmpeg.global.avutil.AV_PICTURE_TYPE_I;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_NONE;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_free;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_set;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_alloc;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_free;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_make_writable;
import static org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR;
import static org.bytedeco.ffmpeg.global.swscale.sws_freeContext;
import static org.bytedeco.ffmpeg.global.swscale.sws_getCachedContext;
import static org.bytedeco.ffmpeg.global.swscale.sws_scale;

public final class H264Avcc420pTranscoder implements VideoFrameTranscoder {

    private AVCodecContext decCtx;
    private AVCodecContext encCtx;
    private AVPacket decPkt;
    private AVPacket encPkt;
    private AVFrame decFrame;
    private AVFrame encFrame;
    private SwsContext sws;
    private int nalLengthSize = 4;
    private byte[] sps;
    private byte[] pps;
    private byte[] encSps;
    private byte[] encPps;
    private byte[] lastSequenceHeaderBytes;
    private byte[] pendingSequenceHeaderBytes;
    private boolean decoderReady;
    private boolean encoderReady;
    private boolean decoderPrimed;
    private long encPts;

    @Override
    public List<InboundMediaFrame> transcode(CanonicalVideoFrame frame, StreamKey derivedKey) {
        if (frame == null || derivedKey == null || frame.sourceFrame() == null) {
            return Collections.emptyList();
        }
        if (frame.payloadFormat() != VideoPayloadFormat.H264_AVCC) {
            return Collections.emptyList();
        }
        if (frame.configFrame()) {
            applyCodecConfig(frame.h264CodecConfig());
            return Collections.emptyList();
        }
        H264CodecConfig codecConfig = frame.h264CodecConfig();
        if (codecConfig != null) {
            applyCodecConfig(codecConfig);
        }
        if (!hasInputParameterSets()) {
            return Collections.emptyList();
        }
        if (!decoderPrimed) {
            if (!frame.keyFrame() || !containsAvccIdr(frame.payload())) {
                return Collections.emptyList();
            }
            decoderPrimed = true;
        }
        byte[] annexb = avccToAnnexb(frame.payload(), frame.keyFrame());
        if (annexb == null || annexb.length == 0) {
            return Collections.emptyList();
        }
        if (!ensureDecoder()) {
            return Collections.emptyList();
        }
        if (!decodePacket(annexb, frame.sourceFrame().ptsMillis(), frame.sourceFrame().dtsMillis())) {
            return Collections.emptyList();
        }
        return drainDecodedFrames(frame, derivedKey);
    }

    private List<InboundMediaFrame> drainDecodedFrames(CanonicalVideoFrame sourceFrame, StreamKey derivedKey) {
        List<InboundMediaFrame> outputs = new ArrayList<InboundMediaFrame>(2);
        int rc;
        while ((rc = avcodec_receive_frame(decCtx, decFrame)) >= 0) {
            if (!ensureEncoder(decFrame)) {
                break;
            }
            AVFrame frameForEncode = prepareFrameForEncode(decFrame);
            if (frameForEncode == null) {
                break;
            }
            if (sourceFrame.keyFrame()) {
                frameForEncode.key_frame(1);
                frameForEncode.pict_type(AV_PICTURE_TYPE_I);
            }
            frameForEncode.pts(encPts++);
            rc = avcodec_send_frame(encCtx, frameForEncode);
            if (rc < 0) {
                continue;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
            while ((rc = avcodec_receive_packet(encCtx, encPkt)) >= 0) {
                byte[] chunk = new byte[encPkt.size()];
                encPkt.data().position(0).get(chunk);
                appendNormalizedAnnexbChunk(baos, chunk);
                av_packet_unref(encPkt);
            }
            byte[] encodedAnnexb = baos.toByteArray();
            if (encodedAnnexb.length == 0) {
                continue;
            }
            AvccTranscodeOutput output = annexbToAvccPayload(encodedAnnexb);
            if (output == null) {
                continue;
            }
            if (output.sequenceHeaderBytes != null) {
                outputs.add(buildOutputFrame(sourceFrame.sourceFrame(), derivedKey, output.sequenceHeaderBytes, true, true));
            }
            outputs.add(buildOutputFrame(sourceFrame.sourceFrame(), derivedKey, output.payloadBytes, output.keyFrame, false));
        }
        return outputs;
    }

    private InboundMediaFrame buildOutputFrame(
            InboundMediaFrame sourceFrame,
            StreamKey derivedKey,
            byte[] payload,
            boolean keyFrame,
            boolean configFrame
    ) {
        return new InboundMediaFrame(
                sourceFrame.sourceProtocol(),
                TrackType.VIDEO,
                CodecType.H264,
                sourceFrame.sessionId(),
                derivedKey,
                sourceFrame.trackId(),
                sourceFrame.ptsMillis(),
                sourceFrame.dtsMillis(),
                keyFrame,
                configFrame,
                sourceFrame.remoteAddress(),
                payload
        );
    }

    private void applyCodecConfig(H264CodecConfig codecConfig) {
        if (codecConfig == null) {
            return;
        }
        nalLengthSize = codecConfig.nalLengthSize();
        sps = codecConfig.sps();
        pps = codecConfig.pps();
        decoderPrimed = false;
    }

    private boolean hasInputParameterSets() {
        return sps != null && sps.length > 0 && pps != null && pps.length > 0;
    }

    private boolean containsAvccIdr(byte[] payload) {
        if (payload == null || payload.length < 5) {
            return false;
        }
        int off = 0;
        int end = payload.length;
        int lenSize = nalLengthSize <= 0 ? 4 : nalLengthSize;
        while (off + lenSize <= end) {
            int naluLen = 0;
            for (int i = 0; i < lenSize; i++) {
                naluLen = (naluLen << 8) | (payload[off + i] & 0xFF);
            }
            off += lenSize;
            if (naluLen <= 0 || off + naluLen > end) {
                return false;
            }
            int nalType = payload[off] & 0x1F;
            if (nalType == 5) {
                return true;
            }
            off += naluLen;
        }
        return false;
    }

    private byte[] avccToAnnexb(byte[] payload, boolean keyFrame) {
        if (payload == null || payload.length < 5) {
            return null;
        }
        int off = 0;
        int end = payload.length;
        byte[] out = new byte[payload.length * 2 + 128];
        int wp = 0;
        if (keyFrame) {
            wp = appendStartCodeNalu(out, wp, sps);
            wp = appendStartCodeNalu(out, wp, pps);
        }
        while (off + nalLengthSize <= end) {
            int len = 0;
            for (int i = 0; i < nalLengthSize; i++) {
                len = (len << 8) | (payload[off + i] & 0xFF);
            }
            off += nalLengthSize;
            if (len <= 0 || off + len > end) {
                break;
            }
            if (wp + 4 + len > out.length) {
                out = Arrays.copyOf(out, Math.max(out.length * 2, wp + 4 + len + 64));
            }
            out[wp++] = 0x00;
            out[wp++] = 0x00;
            out[wp++] = 0x00;
            out[wp++] = 0x01;
            System.arraycopy(payload, off, out, wp, len);
            wp += len;
            off += len;
        }
        return wp == 0 ? null : Arrays.copyOf(out, wp);
    }

    private int appendStartCodeNalu(byte[] out, int wp, byte[] nalu) {
        if (nalu == null || nalu.length == 0 || wp + 4 + nalu.length > out.length) {
            return wp;
        }
        out[wp++] = 0x00;
        out[wp++] = 0x00;
        out[wp++] = 0x00;
        out[wp++] = 0x01;
        System.arraycopy(nalu, 0, out, wp, nalu.length);
        return wp + nalu.length;
    }

    private boolean ensureDecoder() {
        if (decoderReady) {
            return true;
        }
        AVCodec codec = avcodec_find_decoder(AV_CODEC_ID_H264);
        if (codec == null) {
            return false;
        }
        decCtx = avcodec_alloc_context3(codec);
        decPkt = av_packet_alloc();
        decFrame = av_frame_alloc();
        if (decCtx == null || decPkt == null || decFrame == null) {
            return false;
        }
        int rc = avcodec_open2(decCtx, codec, (AVDictionary) null);
        if (rc < 0) {
            return false;
        }
        decoderReady = true;
        return true;
    }

    private boolean decodePacket(byte[] annexb, Long ptsMillis, Long dtsMillis) {
        int rc = avPacketUnrefAndNewData(decPkt, annexb);
        if (rc < 0) {
            return false;
        }
        long pts = ptsMillis == null ? 0L : ptsMillis.longValue();
        long dts = dtsMillis == null ? pts : dtsMillis.longValue();
        decPkt.pts(pts);
        decPkt.dts(dts);
        rc = avcodec_send_packet(decCtx, decPkt);
        av_packet_unref(decPkt);
        return rc >= 0;
    }

    private boolean ensureEncoder(AVFrame frame) {
        if (encoderReady) {
            return true;
        }
        AVCodec codec = avcodec_find_encoder_by_name("libx264");
        if (codec == null) {
            codec = avcodec_find_encoder(AV_CODEC_ID_H264);
        }
        if (codec == null) {
            return false;
        }
        encCtx = avcodec_alloc_context3(codec);
        encPkt = av_packet_alloc();
        encFrame = av_frame_alloc();
        if (encCtx == null || encPkt == null || encFrame == null) {
            return false;
        }
        encCtx.width(frame.width());
        encCtx.height(frame.height());
        encCtx.pix_fmt(selectEncoderPixFmt(codec));
        encCtx.time_base().num(1).den(1000);
        encCtx.framerate().num(25).den(1);
        encCtx.gop_size(25);
        encCtx.max_b_frames(0);
        AVDictionary dict = new AVDictionary();
        configureEncoderOptions(codec, dict);
        int rc = avcodec_open2(encCtx, codec, dict);
        av_dict_free(dict);
        if (rc < 0) {
            rc = avcodec_open2(encCtx, codec, (AVDictionary) null);
            if (rc < 0) {
                return false;
            }
        }
        encFrame.format(encCtx.pix_fmt());
        encFrame.width(frame.width());
        encFrame.height(frame.height());
        rc = av_frame_get_buffer(encFrame, 32);
        if (rc < 0) {
            return false;
        }
        encoderReady = true;
        return true;
    }

    private int selectEncoderPixFmt(AVCodec codec) {
        IntPointer pixFmts = codec.pix_fmts();
        if (pixFmts == null) {
            return AV_PIX_FMT_YUV420P;
        }
        for (int i = 0; ; i++) {
            int pf = pixFmts.get(i);
            if (pf == AV_PIX_FMT_NONE) {
                break;
            }
            if (pf == AV_PIX_FMT_YUV420P) {
                return AV_PIX_FMT_YUV420P;
            }
        }
        return AV_PIX_FMT_YUV420P;
    }

    private void configureEncoderOptions(AVCodec codec, AVDictionary dict) {
        if (codec == null || dict == null) {
            return;
        }
        String codecName = codec.name() == null ? "" : codec.name().getString();
        if ("libx264".equalsIgnoreCase(codecName)) {
            av_dict_set(dict, "preset", "ultrafast", 0);
            av_dict_set(dict, "tune", "zerolatency", 0);
            av_dict_set(dict, "profile", "baseline", 0);
            av_dict_set(dict, "level", "3.1", 0);
            av_dict_set(dict, "x264-params",
                    "annexb=1:repeat-headers=1:aud=0:scenecut=0:keyint=25:min-keyint=25:bframes=0:cabac=0:ref=1:weightp=0:8x8dct=0",
                    0);
            return;
        }
        if ("libopenh264".equalsIgnoreCase(codecName)) {
            av_dict_set(dict, "coder", "0", 0);
            av_dict_set(dict, "bf", "0", 0);
            av_dict_set(dict, "g", "25", 0);
            return;
        }
        av_dict_set(dict, "g", "25", 0);
    }

    private AVFrame prepareFrameForEncode(AVFrame src) {
        int rc = av_frame_make_writable(encFrame);
        if (rc < 0) {
            return null;
        }
        sws = sws_getCachedContext(
                sws,
                src.width(),
                src.height(),
                src.format(),
                encFrame.width(),
                encFrame.height(),
                encFrame.format(),
                SWS_BILINEAR,
                null,
                null,
                (DoublePointer) null);
        if (sws == null) {
            return null;
        }
        sws_scale(
                sws,
                new PointerPointer(src.data()),
                src.linesize(),
                0,
                src.height(),
                new PointerPointer(encFrame.data()),
                encFrame.linesize());
        return encFrame;
    }

    private AvccTranscodeOutput annexbToAvccPayload(byte[] annexb) {
        List<byte[]> nalus = splitAnnexbNalUnits(annexb);
        if (nalus.isEmpty()) {
            return null;
        }
        List<byte[]> keep = new ArrayList<byte[]>(nalus.size());
        boolean key = false;
        int total = 5;
        for (byte[] nalu : nalus) {
            if (nalu == null || nalu.length == 0) {
                continue;
            }
            int type = nalu[0] & 0x1F;
            if (type == 9) {
                continue;
            }
            if (type == 7) {
                encSps = nalu;
            } else if (type == 8) {
                encPps = nalu;
            } else if (type == 5) {
                key = true;
            }
            keep.add(nalu);
            total += 4 + nalu.length;
        }
        if (keep.isEmpty()) {
            return null;
        }
        if (encSps != null && encPps != null) {
            byte[] sequenceHeaderBytes = buildSequenceHeader(encSps, encPps);
            if (!Arrays.equals(sequenceHeaderBytes, lastSequenceHeaderBytes)) {
                lastSequenceHeaderBytes = sequenceHeaderBytes;
                pendingSequenceHeaderBytes = sequenceHeaderBytes;
            }
        }
        ByteBuf out = Unpooled.buffer(total - 5);
        for (byte[] nalu : keep) {
            out.writeInt(nalu.length);
            out.writeBytes(nalu);
        }
        byte[] payloadBytes = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), payloadBytes);
        byte[] sequenceHeaderBytes = pendingSequenceHeaderBytes;
        pendingSequenceHeaderBytes = null;
        return new AvccTranscodeOutput(payloadBytes, sequenceHeaderBytes, key);
    }

    private byte[] buildSequenceHeader(byte[] spsNalu, byte[] ppsNalu) {
        if (spsNalu == null || spsNalu.length < 4 || ppsNalu == null || ppsNalu.length == 0) {
            return null;
        }
        ByteBuf seq = Unpooled.buffer(6 + 2 + spsNalu.length + 1 + 2 + ppsNalu.length);
        seq.writeByte(0x01);
        seq.writeByte(spsNalu[1] & 0xFF);
        seq.writeByte(spsNalu[2] & 0xFF);
        seq.writeByte(spsNalu[3] & 0xFF);
        seq.writeByte(0xFF);
        seq.writeByte(0xE1);
        seq.writeShort(spsNalu.length);
        seq.writeBytes(spsNalu);
        seq.writeByte(0x01);
        seq.writeShort(ppsNalu.length);
        seq.writeBytes(ppsNalu);
        byte[] bytes = new byte[seq.readableBytes()];
        seq.getBytes(seq.readerIndex(), bytes);
        return bytes;
    }

    private List<byte[]> splitAnnexbNalUnits(byte[] bytes) {
        List<byte[]> list = new ArrayList<byte[]>();
        int index = 0;
        while (index + 3 < bytes.length) {
            int start = findStartCode(bytes, index);
            if (start < 0) {
                break;
            }
            int prefix = bytes[start + 2] == 0x01 ? 3 : 4;
            int naluStart = start + prefix;
            int next = findStartCode(bytes, naluStart);
            int naluEnd = next < 0 ? bytes.length : next;
            if (naluEnd > naluStart) {
                list.add(Arrays.copyOfRange(bytes, naluStart, naluEnd));
            }
            index = naluEnd;
        }
        return list;
    }

    private int findStartCode(byte[] bytes, int from) {
        for (int i = Math.max(0, from); i + 3 < bytes.length; i++) {
            if (bytes[i] == 0x00 && bytes[i + 1] == 0x00) {
                if (bytes[i + 2] == 0x01) {
                    return i;
                }
                if (i + 3 < bytes.length && bytes[i + 2] == 0x00 && bytes[i + 3] == 0x01) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void appendNormalizedAnnexbChunk(ByteArrayOutputStream out, byte[] chunk) {
        if (chunk == null || chunk.length == 0) {
            return;
        }
        if (looksLikeAnnexb(chunk)) {
            out.write(chunk, 0, chunk.length);
            return;
        }
        int off = 0;
        while (off + 4 <= chunk.length) {
            int len = ((chunk[off] & 0xFF) << 24)
                    | ((chunk[off + 1] & 0xFF) << 16)
                    | ((chunk[off + 2] & 0xFF) << 8)
                    | (chunk[off + 3] & 0xFF);
            off += 4;
            if (len <= 0 || off + len > chunk.length) {
                out.write(chunk, 0, chunk.length);
                return;
            }
            out.write(0x00);
            out.write(0x00);
            out.write(0x00);
            out.write(0x01);
            out.write(chunk, off, len);
            off += len;
        }
    }

    private boolean looksLikeAnnexb(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return false;
        }
        for (int i = 0; i + 3 < bytes.length; i++) {
            if (bytes[i] == 0x00 && bytes[i + 1] == 0x00) {
                if (bytes[i + 2] == 0x01) {
                    return true;
                }
                if (i + 3 < bytes.length && bytes[i + 2] == 0x00 && bytes[i + 3] == 0x01) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void close() {
        if (sws != null) {
            sws_freeContext(sws);
            sws = null;
        }
        if (decFrame != null) {
            AVFrame tmp = decFrame;
            av_frame_free(tmp);
            decFrame = null;
        }
        if (encFrame != null) {
            AVFrame tmp = encFrame;
            av_frame_free(tmp);
            encFrame = null;
        }
        if (decPkt != null) {
            AVPacket tmp = decPkt;
            av_packet_free(tmp);
            decPkt = null;
        }
        if (encPkt != null) {
            AVPacket tmp = encPkt;
            av_packet_free(tmp);
            encPkt = null;
        }
        if (decCtx != null) {
            AVCodecContext tmp = decCtx;
            avcodec_free_context(tmp);
            decCtx = null;
        }
        if (encCtx != null) {
            AVCodecContext tmp = encCtx;
            avcodec_free_context(tmp);
            encCtx = null;
        }
        decoderReady = false;
        encoderReady = false;
        decoderPrimed = false;
    }

    private static int avPacketUnrefAndNewData(AVPacket packet, byte[] data) {
        av_packet_unref(packet);
        int rc = org.bytedeco.ffmpeg.global.avcodec.av_new_packet(packet, data.length);
        if (rc < 0) {
            return rc;
        }
        packet.data().position(0).put(data);
        return 0;
    }
}
