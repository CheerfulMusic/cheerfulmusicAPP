package com.qingfeng.music.credits;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.usage.UsageBudget.Kind;
import java.util.Objects;
import java.util.function.Supplier;

/** A server-issued quote, active only while dispatching its one approved business request. */
public record ConfirmedCreation(String owner, String work, String revision, long workVersion,
                                Kind kind, String key, long credits) {
    private static final ThreadLocal<ConfirmedCreation> ACTIVE = new ThreadLocal<>();

    public static ConfirmedCreation current() { return ACTIVE.get(); }

    public <T> T dispatch(Supplier<T> action) {
        var previous = ACTIVE.get();
        ACTIVE.set(this);
        try { return action.get(); }
        finally { if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous); }
    }

    public void checkTarget(String owner, String work, String revision, long version) {
        checkWork(owner, work, version);
        if (!Objects.equals(this.revision, revision))
            throw new DomainException("AGENT_QUOTE_STALE", "作品或歌词版本已变化，请重新获取报价并确认。");
    }

    public void checkWork(String owner, String work, long version) {
        if (!this.owner.equals(owner) || !this.work.equals(work) || workVersion != version)
            throw new DomainException("AGENT_QUOTE_STALE", "作品或歌词版本已变化，请重新获取报价并确认。");
    }

    public void checkCharge(String owner, String work, String key, Kind kind, long amount) {
        if (!this.owner.equals(owner) || !this.work.equals(work) || !this.key.equals(key) || this.kind != kind || credits != amount)
            throw new DomainException("AGENT_QUOTE_CHANGED", "本次创作的价格或目标已变化，请重新获取报价并确认。");
    }
}
