package com.wenting.mediaserver.protocol.rtmp;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.DefaultPublishedStream;
import com.wenting.mediaserver.core.publish.IPublishedStream;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.core.track.ITrack;
import com.wenting.mediaserver.core.codec.rtmp.RtmpAudioMessage;
import com.wenting.mediaserver.core.codec.rtmp.RtmpCommandMessage;
import com.wenting.mediaserver.core.codec.rtmp.RtmpMessage;
import com.wenting.mediaserver.core.codec.rtmp.RtmpSetChunkSizeMessage;
import com.wenting.mediaserver.core.codec.rtmp.RtmpSetPeerBandwidthMessage;
import com.wenting.mediaserver.core.codec.rtmp.RtmpVideoMessage;
import com.wenting.mediaserver.core.codec.rtmp.RtmpWindowAcknowledgementSizeMessage;
import com.wenting.mediaserver.protocol.rtsp.RtspSession;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal RTMP connection bridge. Handshake and chunk parsing are added later.
 */
public final class RtmpConnectionHandler extends SimpleChannelInboundHandler<RtmpMessage> {

    private static final Logger log = LoggerFactory.getLogger(RtmpConnectionHandler.class);
    private static final int DEFAULT_OUTBOUND_CHUNK_SIZE = 4096;
    private static final int DEFAULT_ACK_WINDOW = 2500000;

    private final RtmpSessionManager sessionManager;
    private final StreamRegistry streamRegistry;
    private final RtmpVideoFrameMapper videoFrameMapper = new RtmpVideoFrameMapper();
    private final RtmpAudioFrameMapper audioFrameMapper = new RtmpAudioFrameMapper();
    private RtmpSession session;
    private RtmpSubscriberAdapter subscriberAdapter;

    public RtmpConnectionHandler(RtmpSessionManager sessionManager) {
        this(null, sessionManager);
    }

    public RtmpConnectionHandler(StreamRegistry streamRegistry, RtmpSessionManager sessionManager) {
        this.streamRegistry = streamRegistry;
        this.sessionManager = sessionManager == null ? new RtmpSessionManager() : sessionManager;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        session = sessionManager.createSession();
        log.info("RTMP connection opened: session={} remote={}", session.sessionId(), ctx.channel().remoteAddress());
        super.channelActive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RtmpMessage msg) {
        session().recordInboundBytes(msg.payloadLength());
        if (log.isDebugEnabled()) {
            log.debug(
                    "RTMP inbound message: session={} type={} bytes={} total={}",
                    session().sessionId(),
                    msg.messageTypeId(),
                    msg.payloadLength(),
                    session().inboundBytes()
            );
        }
        if (msg instanceof RtmpCommandMessage) {
            handleCommand(ctx, (RtmpCommandMessage) msg);
            return;
        }
        if (msg instanceof RtmpVideoMessage) {
            handleVideo(ctx, (RtmpVideoMessage) msg);
            return;
        }
        if (msg instanceof RtmpAudioMessage) {
            handleAudio(ctx, (RtmpAudioMessage) msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (session != null) {
            removeSubscriber();
            sessionManager.remove(session.sessionId());
            log.info(
                    "RTMP connection closed: session={} inboundBytes={} remote={}",
                    session.sessionId(),
                    session.inboundBytes(),
                    ctx.channel().remoteAddress()
            );
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn(
                "RTMP connection error: session={} remote={}",
                session == null ? "unknown" : session.sessionId(),
                ctx.channel().remoteAddress()
        );
        ctx.close();
    }

    RtmpSession session() {
        if (session == null) {
            throw new IllegalStateException("session has not been created yet");
        }
        return session;
    }

    private void handleCommand(ChannelHandlerContext ctx, RtmpCommandMessage message) {
        String commandName = message.commandName();
        if ("connect".equals(commandName)) {
            handleConnect(ctx, message);
            return;
        }
        if ("createStream".equals(commandName)) {
            handleCreateStream(ctx, message);
            return;
        }
        if ("publish".equals(commandName)) {
            handlePublish(ctx, message);
            return;
        }
        if ("play".equals(commandName)) {
            handlePlay(ctx, message);
            return;
        }
        if ("releaseStream".equals(commandName)) {
            handleReleaseStream(ctx, message);
            return;
        }
        if ("FCPublish".equals(commandName)) {
            handleFcPublish(ctx, message);
            return;
        }
        if ("FCUnpublish".equals(commandName)) {
            handleFcUnpublish(ctx, message);
            return;
        }
        if ("deleteStream".equals(commandName)) {
            handleDeleteStream(ctx, message);
            return;
        }
        log.info("RTMP command session={} name={} transactionId={} args={}",
                session().sessionId(), commandName, message.transactionId(), message.arguments());
    }

    private void handleConnect(ChannelHandlerContext ctx, RtmpCommandMessage message) {
        session().touch();
        if (message.commandObject() instanceof Map) {
            Object app = ((Map<?, ?>) message.commandObject()).get("app");
            if (app instanceof String) {
                session().app((String) app);
            }
        }
        ctx.write(new RtmpWindowAcknowledgementSizeMessage(DEFAULT_ACK_WINDOW));
        ctx.write(new RtmpSetPeerBandwidthMessage(DEFAULT_ACK_WINDOW, 2));
        ctx.write(new RtmpSetChunkSizeMessage(DEFAULT_OUTBOUND_CHUNK_SIZE));

        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put("fmsVer", "FMS/3,0,1,123");
        properties.put("capabilities", Double.valueOf(31.0d));

        Map<String, Object> information = new LinkedHashMap<String, Object>();
        information.put("level", "status");
        information.put("code", "NetConnection.Connect.Success");
        information.put("description", "Connection succeeded.");
        information.put("objectEncoding", Double.valueOf(0.0d));

        List<Object> arguments = new ArrayList<Object>();
        arguments.add(information);
        ctx.writeAndFlush(new RtmpCommandMessage(
                3,
                0L,
                0,
                "_result",
                message.transactionId(),
                properties,
                arguments
        ));
    }

    private void handleCreateStream(ChannelHandlerContext ctx, RtmpCommandMessage message) {
        session().messageStreamId(Integer.valueOf(1));
        List<Object> arguments = new ArrayList<Object>();
        arguments.add(Double.valueOf(1.0d));
        ctx.writeAndFlush(new RtmpCommandMessage(
                3,
                0L,
                0,
                "_result",
                message.transactionId(),
                null,
                arguments
        ));
    }

    private void handlePublish(ChannelHandlerContext ctx, RtmpCommandMessage message) {
        if (!message.arguments().isEmpty() && message.arguments().get(0) instanceof String) {
            session().streamName((String) message.arguments().get(0));
        }
        if (session().app() == null || session().app().isEmpty()) {
            session().app("live");
        }
        if (message.messageStreamId() > 0) {
            session().messageStreamId(Integer.valueOf(message.messageStreamId()));
        }
        if (session().streamName() != null && !session().streamName().isEmpty()) {
            StreamKey streamKey = new StreamKey(StreamProtocol.RTMP, session().app(), session().streamName());
            session().streamKey(streamKey);
            session().role(RtmpSessionRole.PUBLISHER);
            ensurePublishedStream(streamKey);
        }
        Map<String, Object> information = new LinkedHashMap<String, Object>();
        information.put("level", "status");
        information.put("code", "NetStream.Publish.Start");
        information.put("description", "Start publishing.");
        List<Object> arguments = new ArrayList<Object>();
        arguments.add(information);
        ctx.writeAndFlush(new RtmpCommandMessage(
                5,
                0L,
                message.messageStreamId(),
                "onStatus",
                0.0d,
                null,
                arguments
        ));
    }

    private void handlePlay(ChannelHandlerContext ctx, RtmpCommandMessage message) {
        if (!message.arguments().isEmpty() && message.arguments().get(0) instanceof String) {
            session().streamName((String) message.arguments().get(0));
        }
        if (session().app() == null || session().app().isEmpty()) {
            session().app("live");
        }
        if (message.messageStreamId() > 0) {
            session().messageStreamId(Integer.valueOf(message.messageStreamId()));
        }
        if (session().streamName() != null && !session().streamName().isEmpty()) {
            session().streamKey(new StreamKey(StreamProtocol.RTMP, session().app(), session().streamName()));
        }
        IPublishedStream publishedStream = publishedStream();
        if (publishedStream == null) {
            writePlayStatus(ctx, message.messageStreamId(), "status", "NetStream.Play.StreamNotFound", "Stream not found.");
            return;
        }
        removeSubscriber();
        session().role(RtmpSessionRole.SUBSCRIBER);
        RtmpSubscriberSession subscriberSession = new RtmpSubscriberSession(
                session().sessionId(),
                session().streamKey(),
                ctx.channel(),
                session().messageStreamId()
        );
        RtspSession rtspPublisherSession = findRtspPublisherSession();
        if (rtspPublisherSession != null) {
            for (ITrack track : rtspPublisherSession.trackList()) {
                subscriberSession.track(track);
            }
        }
        subscriberAdapter = new RtmpSubscriberAdapter(subscriberSession);
        writePlayStatus(ctx, message.messageStreamId(), "status", "NetStream.Play.Start", "Start live playback.");
        publishedStream.addSubscriber(subscriberAdapter);
    }

    private void handleReleaseStream(ChannelHandlerContext ctx, RtmpCommandMessage message) {
        if (!message.arguments().isEmpty() && message.arguments().get(0) instanceof String) {
            session().streamName((String) message.arguments().get(0));
        }
        writeCommandResult(ctx, message.transactionId(), null, java.util.Collections.<Object>singletonList(null));
    }

    private void handleFcPublish(ChannelHandlerContext ctx, RtmpCommandMessage message) {
        String streamName = firstStringArgument(message);
        if (streamName != null && !streamName.isEmpty()) {
            session().streamName(streamName);
        }
        writeCommandResult(ctx, message.transactionId(), null, java.util.Collections.<Object>singletonList(null));
    }

    private void handleFcUnpublish(ChannelHandlerContext ctx, RtmpCommandMessage message) {
        String streamName = firstStringArgument(message);
        if (streamName != null && !streamName.isEmpty()) {
            session().streamName(streamName);
        }
        if (session().isPublisher()) {
            removePublishedStream();
            clearStreamingState();
        }
        writeCommandResult(ctx, message.transactionId(), null, java.util.Collections.<Object>singletonList(null));
    }

    private void handleDeleteStream(ChannelHandlerContext ctx, RtmpCommandMessage message) {
        if (session().isSubscriber()) {
            removeSubscriber();
            clearStreamingState();
            return;
        }
        if (session().isPublisher()) {
            removePublishedStream();
            clearStreamingState();
        }
    }

    private void handleVideo(ChannelHandlerContext ctx, RtmpVideoMessage message) {
        if (!acceptsMediaMessage(message.messageStreamId())) {
            return;
        }
        IPublishedStream publishedStream = publishedStream();
        if (publishedStream == null) {
            return;
        }
        publishedStream.onInboundFrame(
                videoFrameMapper.map(session(), message, resolveRemoteAddress(ctx))
        );
    }

    private void handleAudio(ChannelHandlerContext ctx, RtmpAudioMessage message) {
        if (!acceptsMediaMessage(message.messageStreamId())) {
            return;
        }
        IPublishedStream publishedStream = publishedStream();
        if (publishedStream == null) {
            return;
        }
        publishedStream.onInboundFrame(
                audioFrameMapper.map(session(), message, resolveRemoteAddress(ctx))
        );
    }

    private boolean acceptsMediaMessage(int messageStreamId) {
        if (!session().publishingReady()) {
            return false;
        }
        return session().messageStreamId() == null
                || session().messageStreamId().intValue() <= 0
                || session().messageStreamId().intValue() == messageStreamId;
    }

    private IPublishedStream ensurePublishedStream(StreamKey streamKey) {
        if (streamRegistry == null) {
            return new DefaultPublishedStream(streamKey);
        }
        IPublishedStream existing = streamRegistry.findPublishedStream(streamKey);
        if (existing != null) {
            return existing;
        }
        IPublishedStream created = new DefaultPublishedStream(streamKey);
        IPublishedStream previous = streamRegistry.registerPublishedStream(streamKey, created);
        return previous == null ? created : previous;
    }

    private IPublishedStream publishedStream() {
        if (session().streamKey() == null) {
            return null;
        }
        if (streamRegistry == null) {
            return null;
        }
        IPublishedStream stream = streamRegistry.findPublishedStream(session().streamKey());
        if (stream != null) {
            return stream;
        }
        return streamRegistry.findPublishedStreamByPath(session().streamKey().app(), session().streamKey().stream());
    }

    private RtspSession findRtspPublisherSession() {
        if (streamRegistry == null || streamRegistry.getRtspSessionManager() == null || session().streamKey() == null) {
            return null;
        }
        for (RtspSession candidate : streamRegistry.getRtspSessionManager().sessions()) {
            if (candidate == null || !candidate.isPublisher() || candidate.streamKey() == null) {
                continue;
            }
            if (session().streamKey().app().equals(candidate.streamKey().app())
                    && session().streamKey().stream().equals(candidate.streamKey().stream())) {
                return candidate;
            }
        }
        return null;
    }

    private InetSocketAddress resolveRemoteAddress(ChannelHandlerContext ctx) {
        SocketAddress remoteAddress = ctx == null || ctx.channel() == null ? null : ctx.channel().remoteAddress();
        return remoteAddress instanceof InetSocketAddress ? (InetSocketAddress) remoteAddress : null;
    }

    private void writePlayStatus(ChannelHandlerContext ctx, int messageStreamId, String level, String code, String description) {
        Map<String, Object> information = new LinkedHashMap<String, Object>();
        information.put("level", level);
        information.put("code", code);
        information.put("description", description);
        List<Object> arguments = new ArrayList<Object>();
        arguments.add(information);
        ctx.writeAndFlush(new RtmpCommandMessage(
                5,
                0L,
                messageStreamId <= 0 ? 1 : messageStreamId,
                "onStatus",
                0.0d,
                null,
                arguments
        ));
    }

    private void writeCommandResult(ChannelHandlerContext ctx, double transactionId, Object commandObject, List<Object> arguments) {
        ctx.writeAndFlush(new RtmpCommandMessage(
                3,
                0L,
                0,
                "_result",
                transactionId,
                commandObject,
                arguments
        ));
    }

    private void removeSubscriber() {
        if (subscriberAdapter == null || streamRegistry == null || session == null || session.streamKey() == null) {
            subscriberAdapter = null;
            return;
        }
        IPublishedStream publishedStream = streamRegistry.findPublishedStream(session.streamKey());
        if (publishedStream != null) {
            publishedStream.removeSubscriber(subscriberAdapter.sessionId());
        }
        subscriberAdapter = null;
    }

    private void removePublishedStream() {
        if (streamRegistry == null || session == null || session.streamKey() == null) {
            return;
        }
        streamRegistry.removePublishedStream(session.streamKey());
    }

    private void clearStreamingState() {
        session().role(RtmpSessionRole.UNKNOWN);
        session().streamKey(null);
        session().streamName(null);
        session().messageStreamId(null);
    }

    private String firstStringArgument(RtmpCommandMessage message) {
        if (message == null || message.arguments().isEmpty() || !(message.arguments().get(0) instanceof String)) {
            return null;
        }
        return (String) message.arguments().get(0);
    }
}
