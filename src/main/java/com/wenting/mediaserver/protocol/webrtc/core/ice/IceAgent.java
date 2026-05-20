package com.wenting.mediaserver.protocol.webrtc.core.ice;

import com.wenting.mediaserver.protocol.webrtc.api.RTCPeerConnection;
import com.wenting.mediaserver.protocol.webrtc.core.stun.StunConstants;
import com.wenting.mediaserver.protocol.webrtc.core.stun.StunMessage;
import com.wenting.mediaserver.protocol.webrtc.core.stun.StunMessage.Attribute;
import com.wenting.mediaserver.protocol.webrtc.transport.DatagramIo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * ICE agent — 核心状态机，管理 ICE 连接建立过程 (RFC 5245).
 *
 * 状态流转:
 *   NEW → GATHERING → CHECKING → CONNECTED → COMPLETED
 *   任意状态 → FAILED
 *
 * 用法:
 *   IceAgent agent = new IceAgent(transport, IceAgent.Role.CONTROLLING);
 *   agent.addLocalCandidates(gatherer.gather());
 *   agent.setRemoteCandidates(remoteCandidates);
 *   agent.startConnectivityChecks();
 */
public class IceAgent {
    private static final Logger LOG = LoggerFactory.getLogger(IceAgent.class);

    public enum Role { CONTROLLING, CONTROLLED }

    public enum State {
        NEW,            // 初始
        GATHERING,      // 收集中
        CHECKING,       // 连通性检查中
        CONNECTED,      // 至少一个 pair 可用
        COMPLETED,      // 完成（选出了 nominated pair）
        FAILED,         // 失败
        CLOSED
    }

    private static final int CHECK_LIST_SIZE_LIMIT = 100;

    // ---- 健壮性参数 ----

    /** 连通性检查超时 (ms) — 发送 Binding Request 后等待响应的时间 */
    private static final long CHECK_TIMEOUT_MS = 5000;

    /** 每对最大重试次数 */
    private static final int MAX_RETRIES_PER_PAIR = 2;

    /** 周期性检查的间隔 (ms) — 用于解锁 FROZEN pair 或重试 */
    private static final long PERIODIC_CHECK_INTERVAL_MS = 500;

    /**
     * Consent freshness (RFC 7675) 检查间隔 (ms).
     * 连接建立后，每 30s 发送 STUN Binding Request 确认远端仍在。
     */
    private static final long CONSENT_CHECK_INTERVAL_MS = 15000;

    /** Consent freshness 最大连续失败次数，超过则认为连接失效 */
    private static final int MAX_CONSENT_FAILURES = 2;

    private final DatagramIo transport;
    private final Role role;
    private final SecureRandom random = new SecureRandom();
    private final AtomicInteger foundationCounter = new AtomicInteger(0);
    private final List<Consumer<IceEvent>> eventListeners = new CopyOnWriteArrayList<>();
    private final Map<String, CandidatePair> transactionPairs = new ConcurrentHashMap<>();

    // ---- srflx gathering ----
    private final List<InetSocketAddress> stunServers = new ArrayList<>();
    private final Map<String, PendingSrflxRequest> pendingSrflx = new ConcurrentHashMap<>();
    private final AtomicInteger srflxFoundationCounter = new AtomicInteger(0);
    private volatile boolean gatheringComplete = false;

    // ---- nomination ----
    private final long tieBreaker;

    // ---- shared timer ----
    private final ScheduledExecutorService timeoutScheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ice-timeout");
            t.setDaemon(true);
            return t;
        });

    private volatile State state = State.NEW;

    private final List<IceCandidate> localCandidates = new ArrayList<>();
    private final List<IceCandidate> remoteCandidates = new ArrayList<>();
    private final List<CandidatePair> checkList = new ArrayList<>();

    private CandidatePair selectedPair;
    private String ufrag;
    private String upwd;

    // ---- 追踪 check 超时任务，用于取消 ----
    private final ConcurrentHashMap<String, ScheduledFuture<?>> checkTimeouts = new ConcurrentHashMap<>();

    // ---- 当前 in-progress 的 check 数量 ----
    private final AtomicInteger inProgressCount = new AtomicInteger(0);

    // ---- 连通性检查的周期调度任务 ----
    private ScheduledFuture<?> periodicCheckFuture;

    // ---- consent freshness 状态 ----
    private int consentFailureCount = 0;
    private long lastConsentCheckMs = 0;
    private ScheduledFuture<?> consentCheckFuture;

    public IceAgent(DatagramIo transport, Role role) {
        this.transport = transport;
        this.role = Objects.requireNonNull(role);
        this.tieBreaker = new SecureRandom().nextLong() & Long.MAX_VALUE;
    }

    // ---- 候选者管理 ----

    public void addLocalCandidates(List<IceCandidate> candidates) {
        localCandidates.addAll(candidates);
        LOG.info("Added " + candidates.size() + " local candidates");
    }

    /** 设置远端候选者，并构建 check list */
    public void setRemoteCandidates(List<IceCandidate> candidates) {
        this.remoteCandidates.clear();
        this.remoteCandidates.addAll(candidates);
        buildCheckList();
    }

    /** 添加单个远端候选者（ICE trickle 场景） */
    public synchronized void addRemoteCandidate(IceCandidate candidate) {
        remoteCandidates.add(candidate);
        // 与所有本地候选者形成新 pair
        for (IceCandidate local : localCandidates) {
            CandidatePair pair = new CandidatePair(local, candidate);
            checkList.add(pair);
        }
        Collections.sort(checkList);
    }

    // ---- 连通性检查 ----

    /** 启动连通性检查 */
    public synchronized void startConnectivityChecks() {
        if (state != State.NEW && state != State.GATHERING) return;

        // If still gathering srflx candidates, defer starting checks
        if (state == State.GATHERING && !gatheringComplete) {
            LOG.info("Connectivity checks deferred — still gathering srflx candidates");
            return;
        }

        // 如果没 pair，直接失败
        if (checkList.isEmpty()) {
            setState(State.FAILED);
            return;
        }

        setState(State.CHECKING);

        // 启动周期性检查调度器 — 每隔 PERIODIC_CHECK_INTERVAL_MS 解锁 FROZEN pair、处理超时
        schedulePeriodicChecks();
    }

    /**
     * 启动周期性检查调度，持续驱动连通性检查流程。
     * 每次触发时:
     *   1. 检查是否有 IN_PROGRESS 的 pair 已超时
     *   2. 如有需要，解锁更多 FROZEN pair
     */
    private synchronized void schedulePeriodicChecks() {
        if (periodicCheckFuture != null) {
            periodicCheckFuture.cancel(false);
        }
        periodicCheckFuture = timeoutScheduler.scheduleAtFixedRate(
            this::tickChecks, 0, PERIODIC_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** 周期性 tick: 处理超时 + 解锁新 pair */
    private synchronized void tickChecks() {
        if (state == State.FAILED || state == State.CLOSED || state == State.COMPLETED) {
            stopPeriodicChecks();
            return;
        }

        // 1) 处理超时
        long now = System.currentTimeMillis();
        for (CandidatePair pair : checkList) {
            if (pair.getState() != CandidatePair.State.IN_PROGRESS) continue;
            long elapsed = now - pair.getLastCheckTimeMs();
            if (elapsed >= CHECK_TIMEOUT_MS) {
                handleCheckTimeout(pair);
            }
        }

        // 2) 解锁新 pair (最多保持 3 个 IN_PROGRESS)
        int active = inProgressCount.get();
        if (active < 3) {
            for (CandidatePair pair : checkList) {
                if (pair.getState() == CandidatePair.State.FROZEN && active < 3) {
                    performCheck(pair);
                    active++;
                }
            }
        }
    }

    /** 处理单个 check 超时：重试或标记失败 */
    private void handleCheckTimeout(CandidatePair pair) {
        String key = String.valueOf(System.identityHashCode(pair));

        // 取消旧的超时任务
        ScheduledFuture<?> timeoutTask = checkTimeouts.remove(key);
        if (timeoutTask != null) timeoutTask.cancel(false);

        inProgressCount.decrementAndGet();

        if (pair.getRetryCount() < MAX_RETRIES_PER_PAIR) {
            pair.setRetryCount(pair.getRetryCount() + 1);
            LOG.warn("ICE check retry " + pair.getRetryCount() + "/"
                + MAX_RETRIES_PER_PAIR + " for " + pair);
            performCheck(pair);
        } else {
            pair.setState(CandidatePair.State.FAILED);
            LOG.warn("ICE check failed after "
                + (MAX_RETRIES_PER_PAIR + 1) + " attempts: " + pair);
            updateState();
        }
    }

    /** 停止周期性检查 */
    private void stopPeriodicChecks() {
        if (periodicCheckFuture != null) {
            periodicCheckFuture.cancel(false);
            periodicCheckFuture = null;
        }
    }

    /**
     * 处理入站的 STUN 消息（从 UdpTransport 回调接入）。
     */
    public void handleStunMessage(StunMessage msg, InetSocketAddress remoteAddr) {
        // ---- Binding Request (peer connectivity check or nomination) ----
        if (msg.isBindingRequest()) {
            CandidatePair pair = findPairByRemoteAddress(remoteAddr);
            if (pair == null) {
                pair = createPeerReflexivePair(remoteAddr);
                if (pair == null) {
                    LOG.error("Ignoring Binding Request from unknown address: " + remoteAddr);
                    return;
                }
            }

            boolean useCandidate = msg.hasAttribute(StunConstants.ATTR_USE_CANDIDATE);

            // Send Binding Response
            sendBindingResponse(msg, remoteAddr);

            // Update pair state
            if (state == State.NEW || state == State.GATHERING) {
                setState(State.CHECKING);
            }
            boolean isNewSuccess = pair.getState() != CandidatePair.State.SUCCEEDED
                && pair.getState() != CandidatePair.State.NOMINATED;
            if (isNewSuccess) {
                pair.setState(CandidatePair.State.SUCCEEDED);
                fireEvent(new IceEvent(IceEvent.Type.PAIR_SUCCEEDED, pair));
                updateState();
            }

            // Handle nomination from controlling peer (USE-CANDIDATE)
            if (useCandidate && role == Role.CONTROLLED) {
                if (pair.getState() != CandidatePair.State.NOMINATED) {
                    pair.setState(CandidatePair.State.NOMINATED);
                    fireEvent(new IceEvent(IceEvent.Type.NOMINATED, pair));
                    updateState();
                }
            }
            return;
        }

        // ---- Binding Response ----
        if (msg.isBindingResponse()) {
            String txKey = bytesToHex(msg.getTransactionId());

            // Check srflx gathering response first
            PendingSrflxRequest srflxReq = pendingSrflx.remove(txKey);
            if (srflxReq != null) {
                onSrflxResponse(msg, srflxReq.server);
                checkSrflxCompletion();
                return;
            }

            // Connectivity check response
            CandidatePair pair = transactionPairs.remove(txKey);
            if (pair == null) return;

            // 取消该 pair 的超时任务 (check 已完成)
            String checkKey = String.valueOf(System.identityHashCode(pair));
            ScheduledFuture<?> timeoutTask = checkTimeouts.remove(checkKey);
            if (timeoutTask != null) timeoutTask.cancel(false);

            // 重置 consent freshness 失败计数 (收到响应说明远端可达)
            consentFailureCount = 0;

            if (pair.getState() == CandidatePair.State.IN_PROGRESS) {
                inProgressCount.decrementAndGet();
                pair.setState(CandidatePair.State.SUCCEEDED);
                LOG.info("ICE connectivity check succeeded: " + pair);
                fireEvent(new IceEvent(IceEvent.Type.PAIR_SUCCEEDED, pair));
                updateState();

                // If controlling, nominate the selected pair
                if (role == Role.CONTROLLING && selectedPair == pair) {
                    nominatePair(pair);
                }
            } else if (pair.getState() == CandidatePair.State.SUCCEEDED) {
                // Response to a USE-CANDIDATE nomination check
                pair.setState(CandidatePair.State.NOMINATED);
                LOG.info("Pair nominated (confirmed): " + pair);
                fireEvent(new IceEvent(IceEvent.Type.NOMINATED, pair));
                updateState();
            }
        }
    }

    // ---- 事件监听 ----

    public void addEventListener(Consumer<IceEvent> listener) {
        eventListeners.add(listener);
    }

    public void removeEventListener(Consumer<IceEvent> listener) {
        eventListeners.remove(listener);
    }

    // ========================================================================
    // Candidate Gathering (srflx)
    // ========================================================================

    public void setStunServers(List<InetSocketAddress> servers) {
        stunServers.clear();
        stunServers.addAll(servers);
    }

    /**
     * Begin candidate gathering. Fire CANDIDATE_GATHERED for existing host
     * candidates, then send STUN Binding Requests to configured STUN servers
     * to discover server-reflexive candidates.
     */
    public synchronized void startGathering() {
        if (state != State.NEW) return;
        setState(State.GATHERING);

        if (localCandidates.isEmpty()) {
            setState(State.FAILED);
            return;
        }

        // Fire CANDIDATE_GATHERED for host candidates
        for (IceCandidate c : localCandidates) {
            fireEvent(new IceEvent(IceEvent.Type.CANDIDATE_GATHERED, c));
        }

        // If no STUN servers, gathering is complete immediately
        if (stunServers.isEmpty()) {
            gatheringComplete = true;
            return;
        }

        // Send Binding Request to each STUN server for srflx discovery
        for (InetSocketAddress server : stunServers) {
            sendSrflxRequest(server);
        }
    }

    public boolean isGatheringComplete() {
        return gatheringComplete;
    }

    private void sendSrflxRequest(InetSocketAddress server) {
        byte[] transactionId = new byte[12];
        random.nextBytes(transactionId);

        StunMessage request = StunMessage.createIceBindingRequest(
            transactionId, 0, 0, true, false);
        byte[] data = request.encode();

        String key = bytesToHex(transactionId);
        pendingSrflx.put(key, new PendingSrflxRequest(server, transactionId));

        transport.send(data, server).exceptionally(ex -> {
            pendingSrflx.remove(key);
            checkSrflxCompletion();
            return null;
        });

        // Timeout after 5s
        timeoutScheduler.schedule(() -> {
            PendingSrflxRequest removed = pendingSrflx.remove(key);
            if (removed != null) {
                LOG.error("srflx request to " + server + " timed out");
                checkSrflxCompletion();
            }
        }, 5000, TimeUnit.MILLISECONDS);
    }

    private synchronized void onSrflxResponse(StunMessage msg, InetSocketAddress server) {
        InetSocketAddress mappedAddr = msg.getXorMappedAddress();
        if (mappedAddr == null) return;

        // Skip if already have a candidate with the same address
        for (IceCandidate existing : localCandidates) {
            if (existing.getAddress().equals(mappedAddr)) return;
        }

        // Find the base address used to reach the STUN server
        InetSocketAddress baseAddr = localCandidates.isEmpty()
            ? null : localCandidates.get(0).getAddress();

        String foundation = "srflx-" + srflxFoundationCounter.incrementAndGet();
        IceCandidate srflx = new IceCandidate(
            foundation, 1, "UDP", mappedAddr,
            CandidateType.SERVER_REFLEXIVE, baseAddr);

        localCandidates.add(srflx);
        LOG.info("Gathered srflx candidate: " + srflx);
        fireEvent(new IceEvent(IceEvent.Type.CANDIDATE_GATHERED, srflx));
    }

    private synchronized void checkSrflxCompletion() {
        if (pendingSrflx.isEmpty() && !gatheringComplete) {
            gatheringComplete = true;
            LOG.info("srflx gathering complete");
            // Auto-start connectivity checks if remote candidates already added
            if (!remoteCandidates.isEmpty() && state == State.GATHERING) {
                startConnectivityChecks();
            }
        }
    }

    // ========================================================================
    // Nomination (RFC 5245 Section 7.1.3)
    // ========================================================================

    /**
     * Send a USE-CANDIDATE Binding Request to nominate the given pair.
     * CONTROLLING side only.
     */
    private void nominatePair(CandidatePair pair) {
        if (role != Role.CONTROLLING) return;
        if (pair.getState() != CandidatePair.State.SUCCEEDED) return;

        byte[] transactionId = new byte[12];
        random.nextBytes(transactionId);

        StunMessage request = StunMessage.createIceBindingRequest(
            transactionId, pair.getLocal().getPriority(),
            tieBreaker, true, true);
        byte[] data = request.encode();

        String txKey = bytesToHex(transactionId);
        transactionPairs.put(txKey, pair);

        InetSocketAddress target = pair.getRemote().getAddress();
        transport.send(data, target).exceptionally(ex -> {
            transactionPairs.remove(txKey);
            LOG.warn("Nomination request failed: " + ex.getMessage());
            return null;
        });

        LOG.info("Sending nomination (USE-CANDIDATE) for pair: " + pair);
    }

    // ========================================================================
    // ICE Restart (RFC 5245 Section 9.2)
    // ========================================================================

    /**
     * Restart ICE: reset all state, generate new credentials, return to NEW.
     * Caller must re-gather candidates and initiate new connectivity checks.
     *
     * @return {ufrag, pwd}
     */
    public synchronized String[] restartIce() {
        LOG.info("ICE restart initiated");

        localCandidates.clear();
        remoteCandidates.clear();
        checkList.clear();
        transactionPairs.clear();
        pendingSrflx.clear();
        srflxFoundationCounter.set(0);
        gatheringComplete = false;
        selectedPair = null;

        this.state = State.NEW;
        fireEvent(new IceEvent(IceEvent.Type.STATE_CHANGED, (CandidatePair) null));

        // Generate new credentials
        SecureRandom rng = new SecureRandom();
        String newUfrag = generateCredential(rng, 4);
        String newPwd = generateCredential(rng, 22);
        setCredentials(newUfrag, newPwd);

        return new String[]{newUfrag, newPwd};
    }

    /**
     * Shutdown the ICE agent and release timer resources.
     */
    public void shutdown() {
        stopPeriodicChecks();
        stopConsentFreshness();
        for (ScheduledFuture<?> ft : checkTimeouts.values()) {
            ft.cancel(false);
        }
        checkTimeouts.clear();
        timeoutScheduler.shutdown();
        state = State.CLOSED;
    }

    private static String generateCredential(SecureRandom random, int length) {
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    // ========================================================================
    // Accessors
    // ========================================================================

    public State getState() { return state; }
    public Role getRole() { return role; }
    public List<IceCandidate> getLocalCandidates() { return Collections.unmodifiableList(localCandidates); }
    public List<IceCandidate> getRemoteCandidates() { return Collections.unmodifiableList(remoteCandidates); }
    public List<CandidatePair> getCheckList() { return Collections.unmodifiableList(checkList); }
    public CandidatePair getSelectedPair() { return selectedPair; }

    public void setCredentials(String ufrag, String upwd) {
        this.ufrag = ufrag;
        this.upwd = upwd;
    }

    // ---- 内部方法 ----

    private synchronized void setState(State newState) {
        State old = this.state;
        this.state = newState;
        LOG.debug("ICE state: " + old + " -> " + newState);
        fireEvent(new IceEvent(IceEvent.Type.STATE_CHANGED, (CandidatePair) null));
    }

    private void buildCheckList() {
        checkList.clear();
        for (IceCandidate local : localCandidates) {
            for (IceCandidate remote : remoteCandidates) {
                if (local.getTransport().equals(remote.getTransport())) {
                    checkList.add(new CandidatePair(local, remote));
                }
            }
        }
        Collections.sort(checkList);

        if (checkList.size() > CHECK_LIST_SIZE_LIMIT) {
            checkList.subList(CHECK_LIST_SIZE_LIMIT, checkList.size()).clear();
        }
        LOG.info("Built check list with " + checkList.size() + " pairs");
    }

    private void performCheck(CandidatePair pair) {
        pair.setState(CandidatePair.State.IN_PROGRESS);
        pair.setLastCheckTimeMs(System.currentTimeMillis());
        inProgressCount.incrementAndGet();

        byte[] transactionId = new byte[12];
        random.nextBytes(transactionId);

        boolean controlling = role == Role.CONTROLLING;
        StunMessage request = StunMessage.createIceBindingRequest(
            transactionId, pair.getLocal().getPriority(),
            tieBreaker, controlling, false);
        byte[] data = request.encode();

        // 注册 transaction -> pair 映射，用于匹配 Binding Response
        String txKey = bytesToHex(transactionId);
        transactionPairs.put(txKey, pair);

        // 设置 check 超时: 如果超时未收到响应，触发超时处理
        String pairKey = String.valueOf(System.identityHashCode(pair));
        ScheduledFuture<?> timeoutTask = timeoutScheduler.schedule(() -> {
            // 只有当 pair 仍在 IN_PROGRESS 时才处理超时
            if (pair.getState() == CandidatePair.State.IN_PROGRESS) {
                // 从 transaction 映射中移除（如果还在的话）
                transactionPairs.remove(txKey);
                handleCheckTimeout(pair);
            }
        }, CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        checkTimeouts.put(pairKey, timeoutTask);

        InetSocketAddress target = pair.getRemote().getAddress();
        transport.send(data, target).exceptionally(ex -> {
            transactionPairs.remove(txKey);
            ScheduledFuture<?> ft = checkTimeouts.remove(pairKey);
            if (ft != null) ft.cancel(false);
            inProgressCount.decrementAndGet();
            handleCheckTimeout(pair);
            return null;
        });

        LOG.info("ICE check: " + pair);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 发送 consent freshness STUN Binding Request (RFC 7675).
     * 定期检查远端是否仍可达。
     */
    private void sendConsentRequest() {
        if (selectedPair == null) return;

        byte[] transactionId = new byte[12];
        random.nextBytes(transactionId);

        boolean controlling = role == Role.CONTROLLING;
        StunMessage request = StunMessage.createIceBindingRequest(
            transactionId, selectedPair.getLocal().getPriority(),
            tieBreaker, controlling, false);
        byte[] data = request.encode();

        String txKey = bytesToHex(transactionId);
        transactionPairs.put(txKey, selectedPair);

        InetSocketAddress target = selectedPair.getRemote().getAddress();
        transport.send(data, target).exceptionally(ex -> {
            transactionPairs.remove(txKey);
            onConsentFailure();
            return null;
        });
    }

    /**
     * 处理 consent freshness 检查失败。
     */
    private synchronized void onConsentFailure() {
        consentFailureCount++;
        long now = System.currentTimeMillis();
        if (now - lastConsentCheckMs > CONSENT_CHECK_INTERVAL_MS) {
            lastConsentCheckMs = now;
            // 只有连续失败才触发
        }
        LOG.warn("Consent freshness check failed (" + consentFailureCount
            + "/" + MAX_CONSENT_FAILURES + ")");
        if (consentFailureCount >= MAX_CONSENT_FAILURES) {
            LOG.info("Connection lost: consent freshness failed after "
                + MAX_CONSENT_FAILURES + " attempts");
            setState(State.FAILED);
        }
    }

    /**
     * 启动 consent freshness 检查 (连接建立后定期发送 STUN Binding Request)。
     */
    private void startConsentFreshness() {
        stopConsentFreshness();
        consentFailureCount = 0;
        consentCheckFuture = timeoutScheduler.scheduleAtFixedRate(
            this::sendConsentRequest,
            CONSENT_CHECK_INTERVAL_MS, CONSENT_CHECK_INTERVAL_MS,
            TimeUnit.MILLISECONDS);
        LOG.debug("Consent freshness checks started (interval="
            + CONSENT_CHECK_INTERVAL_MS + "ms)");
    }

    private void stopConsentFreshness() {
        if (consentCheckFuture != null) {
            consentCheckFuture.cancel(false);
            consentCheckFuture = null;
        }
    }

    private void sendBindingResponse(StunMessage request, InetSocketAddress target) {
        byte[] txId = request.getTransactionId();
        StunMessage response = StunMessage.createBindingResponse(txId, target, null);
        byte[] data = response.encode(upwd);
        transport.send(data, target);
    }

    /** 在 check list 中找 remote address 匹配的 pair */
    private CandidatePair findPairByRemoteAddress(InetSocketAddress remoteAddr) {
        for (CandidatePair p : checkList) {
            if (p.getRemote().getAddress().equals(remoteAddr)) {
                return p;
            }
        }
        return null;
    }

    private synchronized CandidatePair createPeerReflexivePair(InetSocketAddress remoteAddr) {
        IceCandidate local = bestLocalCandidate();
        if (local == null || remoteAddr == null) {
            return null;
        }
        IceCandidate remote = new IceCandidate(
            "prflx-" + foundationCounter.incrementAndGet(),
            local.getComponentId(),
            local.getTransport(),
            remoteAddr,
            CandidateType.PEER_REFLEXIVE,
            null);
        remoteCandidates.add(remote);
        CandidatePair pair = new CandidatePair(local, remote);
        checkList.add(pair);
        Collections.sort(checkList);
        LOG.debug("Created peer-reflexive ICE pair from Binding Request: " + pair);
        return pair;
    }

    private IceCandidate bestLocalCandidate() {
        IceCandidate best = null;
        for (IceCandidate candidate : localCandidates) {
            if (candidate == null) {
                continue;
            }
            if (best == null || candidate.getPriority() > best.getPriority()) {
                best = candidate;
            }
        }
        return best;
    }

    private CandidatePair findPairByTransaction(byte[] transactionId) {
        String txKey = bytesToHex(transactionId);
        CandidatePair pair = transactionPairs.remove(txKey);
        return pair;
    }

    private synchronized void updateState() {
        boolean anySucceeded = false;
        boolean anyNominated = false;
        boolean allFailed = true;

        for (CandidatePair pair : checkList) {
            if (pair.getState() == CandidatePair.State.NOMINATED) {
                anyNominated = true;
                anySucceeded = true;
                allFailed = false;
                // Prefer nominated pair as selected
                if (selectedPair == null
                    || selectedPair.getState() != CandidatePair.State.NOMINATED) {
                    selectedPair = pair;
                }
            } else if (pair.getState() == CandidatePair.State.SUCCEEDED) {
                anySucceeded = true;
                allFailed = false;
                if (selectedPair == null) {
                    selectedPair = pair;
                }
            }
            if (pair.getState() != CandidatePair.State.FAILED) {
                allFailed = false;
            }
        }

        // COMPLETED takes priority over CONNECTED
        if (anyNominated && (state == State.CHECKING || state == State.CONNECTED)) {
            setState(State.COMPLETED);
            // 连接已确认，启动 consent freshness 检查
            startConsentFreshness();
        } else if (anySucceeded && state == State.CHECKING) {
            setState(State.CONNECTED);
        }

        if (allFailed && !checkList.isEmpty()) {
            setState(State.FAILED);
        }
    }

    private void fireEvent(IceEvent event) {
        for (Consumer<IceEvent> listener : eventListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                LOG.warn("ICE event listener error: " + e.getMessage());
            }
        }
    }

}
