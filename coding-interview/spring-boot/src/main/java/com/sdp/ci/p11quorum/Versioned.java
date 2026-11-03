package com.sdp.ci.p11quorum;

/** A value with a version; higher version wins (last-writer-wins). Production systems use vector clocks or HLCs. */
public record Versioned(String value, long version) {}
