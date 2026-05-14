package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVChannelLayout;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swresample.SwrContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.PointerPointer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_PCM_ALAW;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_PCM_MULAW;
import static org.bytedeco.ffmpeg.global.avcodec.AV_INPUT_BUFFER_PADDING_SIZE;
import static org.bytedeco.ffmpeg.global.avcodec.av_new_packet;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_free;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_unref;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_open2;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet;
import static org.bytedeco.ffmpeg.global.avutil.AV_ROUND_UP;
import static org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16;
import static org.bytedeco.ffmpeg.global.avutil.av_channel_layout_copy;
import static org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default;
import static org.bytedeco.ffmpeg.global.avutil.av_channel_layout_uninit;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_alloc;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_free;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_make_writable;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_unref;
import static org.bytedeco.ffmpeg.global.avutil.av_mallocz;
import static org.bytedeco.ffmpeg.global.avutil.av_rescale_rnd;
import static org.bytedeco.ffmpeg.global.swresample.swr_alloc;
import static org.bytedeco.ffmpeg.global.swresample.swr_alloc_set_opts2;
import static org.bytedeco.ffmpeg.global.swresample.swr_convert;
import static org.bytedeco.ffmpeg.global.swresample.swr_free;
import static org.bytedeco.ffmpeg.global.swresample.swr_get_delay;
import static org.bytedeco.ffmpeg.global.swresample.swr_init;

final class AacToG711Transcoder implements AutoCloseable {

    private static final int TARGET_SAMPLE_RATE = 8000;
    private static final int TARGET_CHANNELS = 1;

    private final CodecType targetCodecType;

    private AVCodecContext decCtx;
    private AVCodecContext encCtx;
    private AVPacket decPkt;
    private AVPacket encPkt;
    private AVFrame decFrame;
    private AVFrame resampledFrame;
    private SwrContext swr;
    private AVChannelLayout targetChannelLayout;
    private byte[] audioSpecificConfig;
    private boolean decoderReady;
    private boolean encoderReady;

    AacToG711Transcoder(CodecType targetCodecType) {
        this.targetCodecType = targetCodecType == CodecType.G711A ? CodecType.G711A : CodecType.G711U;
    }

    List<InboundMediaFrame> transcode(InboundMediaFrame sourceFrame) {
        if (sourceFrame == null || sourceFrame.trackType() != com.wenting.mediaserver.core.enums.publish.TrackType.AUDIO) {
            return Collections.emptyList();
        }
        if (sourceFrame.configFrame()) {
            applyAudioSpecificConfig(sourceFrame.payload());
            return Collections.emptyList();
        }
        if (!ensureDecoder() || !ensureEncoder()) {
            return Collections.emptyList();
        }
        if (!decodePacket(sourceFrame.payload(), sourceFrame.ptsMillis(), sourceFrame.dtsMillis())) {
            return Collections.emptyList();
        }
        return drainDecodedFrames(sourceFrame);
    }

    private void applyAudioSpecificConfig(byte[] configBytes) {
        if (configBytes == null || configBytes.length == 0) {
            return;
        }
        if (Arrays.equals(audioSpecificConfig, configBytes)) {
            return;
        }
        audioSpecificConfig = Arrays.copyOf(configBytes, configBytes.length);
        resetDecoder();
    }

    private boolean ensureDecoder() {
        if (decoderReady) {
            return true;
        }
        if (audioSpecificConfig == null || audioSpecificConfig.length == 0) {
            return false;
        }
        AVCodec codec = avcodec_find_decoder(AV_CODEC_ID_AAC);
        if (codec == null) {
            return false;
        }
        decCtx = avcodec_alloc_context3(codec);
        decPkt = av_packet_alloc();
        decFrame = av_frame_alloc();
        if (decCtx == null || decPkt == null || decFrame == null) {
            return false;
        }
        BytePointer extradata = new BytePointer(av_mallocz(audioSpecificConfig.length + AV_INPUT_BUFFER_PADDING_SIZE));
        extradata.position(0).put(audioSpecificConfig, 0, audioSpecificConfig.length);
        decCtx.extradata(extradata);
        decCtx.extradata_size(audioSpecificConfig.length);
        if (avcodec_open2(decCtx, codec, (org.bytedeco.ffmpeg.avutil.AVDictionary) null) < 0) {
            return false;
        }
        decoderReady = true;
        return true;
    }

    private boolean ensureEncoder() {
        if (encoderReady) {
            return true;
        }
        int codecId = targetCodecType == CodecType.G711A ? AV_CODEC_ID_PCM_ALAW : AV_CODEC_ID_PCM_MULAW;
        AVCodec codec = avcodec_find_encoder(codecId);
        if (codec == null) {
            return false;
        }
        encCtx = avcodec_alloc_context3(codec);
        encPkt = av_packet_alloc();
        resampledFrame = av_frame_alloc();
        targetChannelLayout = new AVChannelLayout();
        if (encCtx == null || encPkt == null || resampledFrame == null) {
            return false;
        }
        av_channel_layout_default(targetChannelLayout, TARGET_CHANNELS);
        encCtx.sample_rate(TARGET_SAMPLE_RATE);
        encCtx.sample_fmt(AV_SAMPLE_FMT_S16);
        encCtx.ch_layout(targetChannelLayout);
        encCtx.time_base().num(1).den(TARGET_SAMPLE_RATE);
        if (avcodec_open2(encCtx, codec, (org.bytedeco.ffmpeg.avutil.AVDictionary) null) < 0) {
            return false;
        }
        encoderReady = true;
        return true;
    }

    private boolean decodePacket(byte[] aacPayload, Long ptsMillis, Long dtsMillis) {
        av_packet_unref(decPkt);
        if (av_new_packet(decPkt, aacPayload.length) < 0) {
            return false;
        }
        decPkt.data().position(0).put(aacPayload, 0, aacPayload.length);
        long pts = ptsMillis == null ? 0L : ptsMillis.longValue();
        long dts = dtsMillis == null ? pts : dtsMillis.longValue();
        decPkt.pts(pts);
        decPkt.dts(dts);
        int rc = avcodec_send_packet(decCtx, decPkt);
        av_packet_unref(decPkt);
        return rc >= 0;
    }

    private List<InboundMediaFrame> drainDecodedFrames(InboundMediaFrame sourceFrame) {
        List<InboundMediaFrame> outputs = new ArrayList<InboundMediaFrame>(2);
        int rc;
        while ((rc = avcodec_receive_frame(decCtx, decFrame)) >= 0) {
            AVFrame encodeFrame = prepareResampledFrame(decFrame);
            if (encodeFrame == null) {
                continue;
            }
            rc = avcodec_send_frame(encCtx, encodeFrame);
            if (rc < 0) {
                continue;
            }
            while ((rc = avcodec_receive_packet(encCtx, encPkt)) >= 0) {
                byte[] payload = new byte[encPkt.size()];
                encPkt.data().position(0).get(payload);
                outputs.add(new InboundMediaFrame(
                        sourceFrame.sourceProtocol(),
                        sourceFrame.trackType(),
                        targetCodecType,
                        sourceFrame.sessionId(),
                        sourceFrame.streamKey(),
                        targetCodecType == CodecType.G711A ? "audio-g711a" : "audio-g711u",
                        sourceFrame.ptsMillis(),
                        sourceFrame.dtsMillis(),
                        false,
                        false,
                        sourceFrame.remoteAddress(),
                        payload
                ));
                av_packet_unref(encPkt);
            }
        }
        return outputs;
    }

    private AVFrame prepareResampledFrame(AVFrame sourceFrame) {
        if (sourceFrame == null) {
            return null;
        }
        AVChannelLayout sourceChannelLayout = new AVChannelLayout();
        if (sourceFrame.ch_layout() != null && sourceFrame.ch_layout().nb_channels() > 0) {
            av_channel_layout_copy(sourceChannelLayout, sourceFrame.ch_layout());
        } else if (decCtx.ch_layout() != null && decCtx.ch_layout().nb_channels() > 0) {
            av_channel_layout_copy(sourceChannelLayout, decCtx.ch_layout());
        } else {
            av_channel_layout_default(sourceChannelLayout, 2);
        }
        if (swr == null) {
            swr = swr_alloc();
        }
        int srcRate = sourceFrame.sample_rate() > 0 ? sourceFrame.sample_rate() : decCtx.sample_rate();
        if (swr == null || swr_alloc_set_opts2(
                swr,
                targetChannelLayout,
                AV_SAMPLE_FMT_S16,
                TARGET_SAMPLE_RATE,
                sourceChannelLayout,
                sourceFrame.format(),
                srcRate,
                0,
                null
        ) < 0 || swr_init(swr) < 0) {
            av_channel_layout_uninit(sourceChannelLayout);
            return null;
        }
        int dstSamples = (int) av_rescale_rnd(
                swr_get_delay(swr, srcRate) + sourceFrame.nb_samples(),
                TARGET_SAMPLE_RATE,
                srcRate,
                AV_ROUND_UP
        );
        av_frame_unref(resampledFrame);
        resampledFrame.format(AV_SAMPLE_FMT_S16);
        resampledFrame.ch_layout(targetChannelLayout);
        resampledFrame.sample_rate(TARGET_SAMPLE_RATE);
        resampledFrame.nb_samples(dstSamples);
        if (av_frame_get_buffer(resampledFrame, 0) < 0) {
            av_channel_layout_uninit(sourceChannelLayout);
            return null;
        }
        if (av_frame_make_writable(resampledFrame) < 0) {
            av_channel_layout_uninit(sourceChannelLayout);
            return null;
        }
        int samples = swr_convert(
                swr,
                new PointerPointer(resampledFrame.data()),
                dstSamples,
                new PointerPointer(sourceFrame.data()),
                sourceFrame.nb_samples()
        );
        if (samples <= 0) {
            av_channel_layout_uninit(sourceChannelLayout);
            return null;
        }
        resampledFrame.nb_samples(samples);
        av_channel_layout_uninit(sourceChannelLayout);
        return resampledFrame;
    }

    private void resetDecoder() {
        if (decFrame != null) {
            AVFrame tmp = decFrame;
            av_frame_free(tmp);
            decFrame = null;
        }
        if (decPkt != null) {
            AVPacket tmp = decPkt;
            av_packet_free(tmp);
            decPkt = null;
        }
        if (decCtx != null) {
            AVCodecContext tmp = decCtx;
            avcodec_free_context(tmp);
            decCtx = null;
        }
        decoderReady = false;
    }

    @Override
    public void close() {
        resetDecoder();
        if (resampledFrame != null) {
            AVFrame tmp = resampledFrame;
            av_frame_free(tmp);
            resampledFrame = null;
        }
        if (encPkt != null) {
            AVPacket tmp = encPkt;
            av_packet_free(tmp);
            encPkt = null;
        }
        if (encCtx != null) {
            AVCodecContext tmp = encCtx;
            avcodec_free_context(tmp);
            encCtx = null;
        }
        if (swr != null) {
            SwrContext tmp = swr;
            swr_free(tmp);
            swr = null;
        }
        if (targetChannelLayout != null) {
            av_channel_layout_uninit(targetChannelLayout);
            targetChannelLayout = null;
        }
        encoderReady = false;
    }
}
