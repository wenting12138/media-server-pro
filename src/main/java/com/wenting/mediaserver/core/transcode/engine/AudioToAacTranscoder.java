package com.wenting.mediaserver.core.transcode.engine;

import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.transcode.canonical.CanonicalAudioFrame;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVAudioFifo;
import org.bytedeco.ffmpeg.avutil.AVChannelLayout;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swresample.SwrContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_OPUS;
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
import static org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_FLTP;
import static org.bytedeco.ffmpeg.global.avutil.av_channel_layout_copy;
import static org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default;
import static org.bytedeco.ffmpeg.global.avutil.av_channel_layout_uninit;
import static org.bytedeco.ffmpeg.global.avutil.av_audio_fifo_alloc;
import static org.bytedeco.ffmpeg.global.avutil.av_audio_fifo_free;
import static org.bytedeco.ffmpeg.global.avutil.av_audio_fifo_read;
import static org.bytedeco.ffmpeg.global.avutil.av_audio_fifo_realloc;
import static org.bytedeco.ffmpeg.global.avutil.av_audio_fifo_size;
import static org.bytedeco.ffmpeg.global.avutil.av_audio_fifo_write;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_alloc;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_free;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_make_writable;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_unref;
import static org.bytedeco.ffmpeg.global.avutil.av_mallocz;
import static org.bytedeco.ffmpeg.global.avutil.av_opt_set;
import static org.bytedeco.ffmpeg.global.avutil.av_rescale_rnd;
import static org.bytedeco.ffmpeg.global.swresample.swr_alloc;
import static org.bytedeco.ffmpeg.global.swresample.swr_alloc_set_opts2;
import static org.bytedeco.ffmpeg.global.swresample.swr_convert;
import static org.bytedeco.ffmpeg.global.swresample.swr_free;
import static org.bytedeco.ffmpeg.global.swresample.swr_get_delay;
import static org.bytedeco.ffmpeg.global.swresample.swr_init;

public final class AudioToAacTranscoder implements AudioFrameTranscoder {

    private static final int TARGET_SAMPLE_RATE = 48000;
    private static final int TARGET_CHANNELS = 2;
    private static final int TARGET_BIT_RATE = 128_000;
    private static final int AAC_OBJECT_TYPE_LC = 2;

    private CodecType currentSourceCodecType = CodecType.UNKNOWN;
    private AVCodecContext decCtx;
    private AVCodecContext encCtx;
    private AVPacket decPkt;
    private AVPacket encPkt;
    private AVFrame decFrame;
    private AVFrame resampledFrame;
    private AVFrame encodeFrame;
    private AVAudioFifo audioFifo;
    private SwrContext swr;
    private AVChannelLayout targetChannelLayout;
    private byte[] audioSpecificConfig;
    private boolean decoderReady;
    private boolean encoderReady;
    private boolean encoderConfigSent;
    private byte[] encoderAudioSpecificConfig;

    @Override
    public List<InboundMediaFrame> transcode(CanonicalAudioFrame frame, StreamKey derivedKey) {
        if (frame == null || frame.sourceFrame() == null) {
            return Collections.emptyList();
        }
        CodecType sourceCodecType = frame.codecType();
        if (frame.configFrame()) {
            if (sourceCodecType == CodecType.AAC || sourceCodecType == CodecType.MPEG4_GENERIC) {
                applyAudioSpecificConfig(frame.payload());
            }
            currentSourceCodecType = sourceCodecType;
            return Collections.emptyList();
        }
        if (!ensureDecoder(sourceCodecType) || !ensureEncoder()) {
            return Collections.emptyList();
        }
        if (!decodePacket(frame.payload(), frame.sourceFrame().ptsMillis(), frame.sourceFrame().dtsMillis())) {
            return Collections.emptyList();
        }
        return drainDecodedFrames(frame.sourceFrame(), derivedKey);
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

    private boolean ensureDecoder(CodecType sourceCodecType) {
        if (sourceCodecType != currentSourceCodecType) {
            currentSourceCodecType = sourceCodecType;
            resetDecoder();
        }
        if (decoderReady) {
            return true;
        }
        int codecId = sourceCodecId(sourceCodecType);
        if (codecId == 0) {
            return false;
        }
        if ((sourceCodecType == CodecType.AAC || sourceCodecType == CodecType.MPEG4_GENERIC)
                && (audioSpecificConfig == null || audioSpecificConfig.length == 0)) {
            return false;
        }
        AVCodec codec = avcodec_find_decoder(codecId);
        if (codec == null) {
            return false;
        }
        decCtx = avcodec_alloc_context3(codec);
        decPkt = av_packet_alloc();
        decFrame = av_frame_alloc();
        if (decCtx == null || decPkt == null || decFrame == null) {
            return false;
        }
        if (sourceCodecType == CodecType.AAC || sourceCodecType == CodecType.MPEG4_GENERIC) {
            BytePointer extradata = new BytePointer(av_mallocz(audioSpecificConfig.length + AV_INPUT_BUFFER_PADDING_SIZE));
            extradata.position(0).put(audioSpecificConfig, 0, audioSpecificConfig.length);
            decCtx.extradata(extradata);
            decCtx.extradata_size(audioSpecificConfig.length);
        } else if (sourceCodecType == CodecType.G711A || sourceCodecType == CodecType.G711U) {
            decCtx.sample_rate(8000);
            av_channel_layout_default(decCtx.ch_layout(), 1);
        }
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
        AVCodec codec = avcodec_find_encoder(AV_CODEC_ID_AAC);
        if (codec == null) {
            return false;
        }
        encCtx = avcodec_alloc_context3(codec);
        encPkt = av_packet_alloc();
        resampledFrame = av_frame_alloc();
        encodeFrame = av_frame_alloc();
        targetChannelLayout = new AVChannelLayout();
        if (encCtx == null || encPkt == null || resampledFrame == null || encodeFrame == null) {
            return false;
        }
        av_channel_layout_default(targetChannelLayout, TARGET_CHANNELS);
        encCtx.sample_rate(TARGET_SAMPLE_RATE);
        encCtx.sample_fmt(resolveEncoderSampleFormat(codec));
        encCtx.ch_layout(targetChannelLayout);
        encCtx.time_base().num(1).den(TARGET_SAMPLE_RATE);
        encCtx.bit_rate(TARGET_BIT_RATE);
        encCtx.profile(1);
        av_opt_set(encCtx.priv_data(), "profile", "aac_low", 0);
        if (avcodec_open2(encCtx, codec, (org.bytedeco.ffmpeg.avutil.AVDictionary) null) < 0) {
            return false;
        }
        audioFifo = av_audio_fifo_alloc(encCtx.sample_fmt(), TARGET_CHANNELS, Math.max(encCtx.frame_size(), 1024) * 4);
        if (audioFifo == null) {
            return false;
        }
        encoderAudioSpecificConfig = buildAacAudioSpecificConfig(encCtx.sample_rate(), encCtx.ch_layout().nb_channels());
        encoderConfigSent = false;
        encoderReady = true;
        return true;
    }

    private int resolveEncoderSampleFormat(AVCodec codec) {
        if (codec == null || codec.sample_fmts() == null) {
            return AV_SAMPLE_FMT_FLTP;
        }
        IntPointer sampleFormats = codec.sample_fmts();
        for (int i = 0; i < 8; i++) {
            int sampleFormat = sampleFormats.get(i);
            if (sampleFormat < 0) {
                break;
            }
            if (sampleFormat == AV_SAMPLE_FMT_FLTP) {
                return sampleFormat;
            }
        }
        return sampleFormats.get(0) < 0 ? AV_SAMPLE_FMT_FLTP : sampleFormats.get(0);
    }

    private int sourceCodecId(CodecType sourceCodecType) {
        if (sourceCodecType == CodecType.AAC || sourceCodecType == CodecType.MPEG4_GENERIC) {
            return AV_CODEC_ID_AAC;
        }
        if (sourceCodecType == CodecType.OPUS) {
            return AV_CODEC_ID_OPUS;
        }
        if (sourceCodecType == CodecType.G711A) {
            return AV_CODEC_ID_PCM_ALAW;
        }
        if (sourceCodecType == CodecType.G711U) {
            return AV_CODEC_ID_PCM_MULAW;
        }
        return 0;
    }

    private boolean decodePacket(byte[] payload, Long ptsMillis, Long dtsMillis) {
        av_packet_unref(decPkt);
        if (av_new_packet(decPkt, payload.length) < 0) {
            return false;
        }
        decPkt.data().position(0).put(payload, 0, payload.length);
        long pts = ptsMillis == null ? 0L : ptsMillis.longValue();
        long dts = dtsMillis == null ? pts : dtsMillis.longValue();
        decPkt.pts(pts);
        decPkt.dts(dts);
        int rc = avcodec_send_packet(decCtx, decPkt);
        av_packet_unref(decPkt);
        return rc >= 0;
    }

    private List<InboundMediaFrame> drainDecodedFrames(InboundMediaFrame sourceFrame, StreamKey derivedKey) {
        List<InboundMediaFrame> outputs = new ArrayList<InboundMediaFrame>(3);
        int rc;
        while ((rc = avcodec_receive_frame(decCtx, decFrame)) >= 0) {
            AVFrame convertedFrame = prepareResampledFrame(decFrame);
            if (convertedFrame == null) {
                continue;
            }
            if (!appendToFifo(convertedFrame)) {
                continue;
            }
            drainEncoderPackets(sourceFrame, derivedKey, outputs);
        }
        return outputs;
    }

    private boolean appendToFifo(AVFrame frame) {
        if (frame == null || audioFifo == null) {
            return false;
        }
        int requiredSize = av_audio_fifo_size(audioFifo) + frame.nb_samples();
        if (av_audio_fifo_realloc(audioFifo, requiredSize) < 0) {
            return false;
        }
        return av_audio_fifo_write(audioFifo, new PointerPointer(frame.data()), frame.nb_samples()) >= frame.nb_samples();
    }

    private void drainEncoderPackets(InboundMediaFrame sourceFrame, StreamKey derivedKey, List<InboundMediaFrame> outputs) {
        if (audioFifo == null || encCtx == null) {
            return;
        }
        int frameSize = Math.max(encCtx.frame_size(), 1024);
        while (av_audio_fifo_size(audioFifo) >= frameSize) {
            if (!prepareEncodeFrame(frameSize)) {
                return;
            }
            if (av_audio_fifo_read(audioFifo, new PointerPointer(encodeFrame.data()), frameSize) < frameSize) {
                return;
            }
            int rc = avcodec_send_frame(encCtx, encodeFrame);
            if (rc < 0) {
                return;
            }
            while ((rc = avcodec_receive_packet(encCtx, encPkt)) >= 0) {
                if (!encoderConfigSent && encoderAudioSpecificConfig != null && encoderAudioSpecificConfig.length > 0) {
                    outputs.add(copyAsDerivedFrame(sourceFrame, derivedKey, true, encoderAudioSpecificConfig));
                    encoderConfigSent = true;
                }
                byte[] payload = new byte[encPkt.size()];
                encPkt.data().position(0).get(payload);
                outputs.add(copyAsDerivedFrame(sourceFrame, derivedKey, false, payload));
                av_packet_unref(encPkt);
            }
        }
    }

    private boolean prepareEncodeFrame(int samples) {
        if (encodeFrame == null || encCtx == null || samples <= 0) {
            return false;
        }
        av_frame_unref(encodeFrame);
        encodeFrame.format(encCtx.sample_fmt());
        encodeFrame.ch_layout(targetChannelLayout);
        encodeFrame.sample_rate(TARGET_SAMPLE_RATE);
        encodeFrame.nb_samples(samples);
        if (av_frame_get_buffer(encodeFrame, 0) < 0) {
            return false;
        }
        return av_frame_make_writable(encodeFrame) >= 0;
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
            av_channel_layout_default(sourceChannelLayout, 1);
        }
        if (swr == null) {
            swr = swr_alloc();
        }
        int srcRate = sourceFrame.sample_rate() > 0 ? sourceFrame.sample_rate() : decCtx.sample_rate();
        if (swr == null || swr_alloc_set_opts2(
                swr,
                targetChannelLayout,
                encCtx.sample_fmt(),
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
        resampledFrame.format(encCtx.sample_fmt());
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

    private byte[] buildAacAudioSpecificConfig(int sampleRate, int channels) {
        int sampleRateIndex = sampleRateIndex(sampleRate);
        int channelConfig = channels <= 0 ? TARGET_CHANNELS : channels;
        int value = (AAC_OBJECT_TYPE_LC << 11) | (sampleRateIndex << 7) | (channelConfig << 3);
        return new byte[]{
                (byte) ((value >> 8) & 0xFF),
                (byte) (value & 0xFF)
        };
    }

    private int sampleRateIndex(int sampleRate) {
        switch (sampleRate) {
            case 96000:
                return 0;
            case 88200:
                return 1;
            case 64000:
                return 2;
            case 48000:
                return 3;
            case 44100:
                return 4;
            case 32000:
                return 5;
            case 24000:
                return 6;
            case 22050:
                return 7;
            case 16000:
                return 8;
            case 12000:
                return 9;
            case 11025:
                return 10;
            case 8000:
                return 11;
            case 7350:
                return 12;
            default:
                return 3;
        }
    }

    private InboundMediaFrame copyAsDerivedFrame(
            InboundMediaFrame sourceFrame,
            StreamKey derivedKey,
            boolean configFrame,
            byte[] payload
    ) {
        return new InboundMediaFrame(
                sourceFrame.sourceProtocol(),
                TrackType.AUDIO,
                CodecType.AAC,
                sourceFrame.sessionId(),
                derivedKey,
                "audio-aac",
                sourceFrame.ptsMillis(),
                sourceFrame.dtsMillis(),
                false,
                configFrame,
                sourceFrame.outOfBandParameterSetsReady(),
                sourceFrame.remoteAddress(),
                payload
        );
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
        if (encodeFrame != null) {
            AVFrame tmp = encodeFrame;
            av_frame_free(tmp);
            encodeFrame = null;
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
        if (audioFifo != null) {
            AVAudioFifo tmp = audioFifo;
            av_audio_fifo_free(tmp);
            audioFifo = null;
        }
        if (targetChannelLayout != null) {
            av_channel_layout_uninit(targetChannelLayout);
            targetChannelLayout = null;
        }
        encoderReady = false;
        encoderConfigSent = false;
        encoderAudioSpecificConfig = null;
    }
}
