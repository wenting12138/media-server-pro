package com.wenting.mediaserver.protocol.webrtc.core.ice;

import java.util.Objects;

/**
 * ICE candidate pair — 本地候选者 + 远端候选者的配对。
 *
 * 对应 RFC 5245 中的 candidate pair，按优先级排序后做连通性检查。
 *
 * 优先级公式:
 *   G = CONTROLLING 方的优先级
 *   D = CONTROLLED 方的优先级
 *   pair_priority = 2^32 * min(G, D) + 2 * max(G, D) + (G > D ? 1 : 0)
 */
public class CandidatePair implements Comparable<CandidatePair> {

    public enum State {
        FROZEN,     // 初始状态，尚未开始检查
        WAITING,    // 等待检查（有更高优先级的 pair 正在检查）
        IN_PROGRESS, // 正在检查（已发送 STUN Binding Request）
        SUCCEEDED,  // 连通性检查成功
        FAILED,     // 连通性检查失败
        NOMINATED;  // 被选为最终使用的 pair

        public boolean isActive() {
            return this == WAITING || this == IN_PROGRESS;
        }
    }

    private final IceCandidate local;
    private final IceCandidate remote;
    private final long priority;
    private State state;
    private volatile long lastCheckTimeMs;
    private volatile int retryCount;

    public CandidatePair(IceCandidate local, IceCandidate remote) {
        this.local = Objects.requireNonNull(local);
        this.remote = Objects.requireNonNull(remote);
        this.state = State.FROZEN;
        this.priority = calculatePairPriority(local.getPriority(), remote.getPriority());
        this.lastCheckTimeMs = 0;
        this.retryCount = 0;
    }

    // ---- 访问器 ----

    public IceCandidate getLocal() { return local; }
    public IceCandidate getRemote() { return remote; }
    public long getPriority() { return priority; }
    public State getState() { return state; }
    public void setState(State state) { this.state = state; }
    public long getLastCheckTimeMs() { return lastCheckTimeMs; }
    public void setLastCheckTimeMs(long time) { this.lastCheckTimeMs = time; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int count) { this.retryCount = count; }

    // ---- Comparable ----

    @Override
    public int compareTo(CandidatePair other) {
        return Long.compare(other.priority, this.priority); // 降序
    }

    @Override
    public String toString() {
        return "Pair{" + local + " <-> " + remote + " state=" + state + "}";
    }

    // ---- 内部方法 ----

    static long calculatePairPriority(long localPrio, long remotePrio) {
        long min = Math.min(localPrio, remotePrio);
        long max = Math.max(localPrio, remotePrio);
        long gog = (localPrio > remotePrio) ? 1 : 0;
        return (1L << 32) * min + 2 * max + gog;
    }
}
