package com.sdp.ci.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock the test moves by hand, so time-window logic is deterministic. */
public final class MutableClock extends Clock {
    private volatile Instant now;

    public MutableClock(Instant start) { this.now = start; }

    public void set(Instant instant) { now = instant; }
    public void advanceMillis(long ms) { now = now.plusMillis(ms); }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
}
