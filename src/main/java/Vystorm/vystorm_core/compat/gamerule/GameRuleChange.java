package Vystorm.vystorm_core.compat.gamerule;

/**
 * Result of {@code GameRulesInterface#set}.
 *
 * @param ok     whether the world now has the value
 * @param id     stable id
 * @param before value before, stable meaning ({@code null} when unknown)
 * @param after  value after the call, read back from the world
 * @param reason {@code null} when ok, else one of {@code unknown-rule}, {@code wrong-type}, {@code out-of-range},
 *               {@code refused} (the server said no), {@code unavailable} (no game-rule access on this server)
 * @since 0.73.0
 */
public record GameRuleChange(boolean ok, String id, Object before, Object after, String reason) {
    public static GameRuleChange failed(String id, Object before, String reason) { return new GameRuleChange(false, id, before, before, reason); }
}
