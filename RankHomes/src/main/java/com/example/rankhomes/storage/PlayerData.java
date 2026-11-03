package com.example.rankhomes.storage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory copy of one player's homes and limit override. */
public final class PlayerData {

    private final Map<String, Home> homes = new ConcurrentHashMap<>();
    private volatile Integer limitOverride;

    public Map<String, Home> homes() {
        return homes;
    }

    /** Admin-set limit, or null when the rank-based limit applies. */
    public Integer limitOverride() {
        return limitOverride;
    }

    public void limitOverride(Integer value) {
        this.limitOverride = value;
    }
}
