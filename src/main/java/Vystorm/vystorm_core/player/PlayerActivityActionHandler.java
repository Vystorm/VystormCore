package Vystorm.vystorm_core.player;

import Vystorm.vystorm_core.runtime.RuntimeActionHub;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Neutral cross-plugin control surface for the shared player activity state. */
public final class PlayerActivityActionHandler implements RuntimeActionHub.Handler {
    private final PlayerActivityService activities;

    public PlayerActivityActionHandler(PlayerActivityService activities) {
        this.activities = Objects.requireNonNull(activities);
    }

    @Override public String id() { return "core-player-activity-actions"; }
    @Override public int priority() { return 1000; }
    @Override public Set<String> actions() { return Set.of("player.activity.signal", "player.activity.context"); }

    @Override
    public CompletionStage<RuntimeActionHub.Response> handle(RuntimeActionHub.Request request) {
        if (request.playerId().isEmpty()) return CompletableFuture.completedFuture(RuntimeActionHub.Response.declined(id(), "missing:playerId"));
        try {
            UUID playerId = request.playerId().orElseThrow();
            if (request.action().equals("player.activity.context")) return CompletableFuture.completedFuture(context(playerId));
            PlayerActivityService.Activity activity = PlayerActivityService.Activity.valueOf(
                    request.values().getOrDefault("activity", "IDLE").toUpperCase(Locale.ROOT));
            long holdMillis = Long.parseLong(request.values().getOrDefault("holdMillis", "10000"));
            String[] flags = request.values().getOrDefault("flags", "").split("\\|");
            PlayerActivityService.State state = activities.signal(playerId, activity, Math.max(0L, holdMillis), flags);
            return CompletableFuture.completedFuture(response(state));
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(RuntimeActionHub.Response.declined(id(), "invalid:" + failure.getMessage()));
        }
    }

    private RuntimeActionHub.Response context(UUID playerId) { return response(activities.state(playerId)); }

    private RuntimeActionHub.Response response(PlayerActivityService.State state) {
        LinkedHashSet<String> tags = new LinkedHashSet<>(state.flags());
        tags.add("ACTIVITY:" + state.primary().name());
        return new RuntimeActionHub.Response(id(), true, "ok", tags,
                Map.of("activity", state.primary().name(), "since", Long.toString(state.since()),
                        "expiresAt", Long.toString(state.expiresAt())), Map.of());
    }
}
