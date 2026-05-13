package com.wenting.mediaserver.protocol.webrtc.core.ice;

/**
 * ICE agent 事件，用于监听连接状态变化和 pair 状态变化。
 */
public class IceEvent {

    public enum Type {
        STATE_CHANGED,      // ICE 状态机变化
        CANDIDATE_GATHERED, // 收集到新候选者
        PAIR_SUCCEEDED,     // 某候选者 pair 连通性检查成功
        NOMINATED,          // 选出最终 pair
        FAILED              // 失败
    }

    private final Type type;
    private final CandidatePair pair;
    private final IceCandidate candidate;

    public IceEvent(Type type, CandidatePair pair) {
        this.type = type;
        this.pair = pair;
        this.candidate = null;
    }

    public IceEvent(Type type, IceCandidate candidate) {
        this.type = type;
        this.pair = null;
        this.candidate = candidate;
    }

    public Type getType() { return type; }
    public CandidatePair getPair() { return pair; }
    public IceCandidate getCandidate() { return candidate; }

    @Override
    public String toString() {
        return "IceEvent{" + type
            + (pair != null ? " " + pair : "")
            + (candidate != null ? " " + candidate : "")
            + "}";
    }
}
