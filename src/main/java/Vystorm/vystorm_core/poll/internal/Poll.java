package Vystorm.vystorm_core.poll.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** One anonymous, single-choice poll. Access on the server thread only. */
final class Poll {
    final String id = UUID.randomUUID().toString();
    final String question;
    final List<String> options;
    final long deadline;
    private final Map<UUID, Integer> votes = new HashMap<>();

    Poll(String input, long now, int seconds, int maxOptions, int maxLength) {
        List<String> parts = java.util.Arrays.stream(input.split("\\|", -1)).map(String::strip).toList();
        if (seconds < 1 || parts.size() < 3 || parts.size() > maxOptions + 1
                || parts.stream().anyMatch(s -> s.isEmpty() || s.length() > maxLength)
                || parts.subList(1, parts.size()).stream().map(s -> s.toLowerCase(Locale.ROOT)).distinct().count() != parts.size() - 1)
            throw new IllegalArgumentException("Invalid poll");
        question = parts.getFirst();
        options = List.copyOf(parts.subList(1, parts.size()));
        deadline = now + seconds * 1000L;
    }

    boolean vote(UUID player, int option, long now) {
        if (now >= deadline || option < 1 || option > options.size()) return false;
        votes.put(player, option - 1);
        return true;
    }

    int[] counts() {
        int[] counts = new int[options.size()];
        votes.values().forEach(i -> counts[i]++);
        return counts;
    }

    int voters() { return votes.size(); }
}
