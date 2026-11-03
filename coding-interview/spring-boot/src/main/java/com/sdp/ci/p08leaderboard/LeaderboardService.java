package com.sdp.ci.p08leaderboard;

import java.util.List;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Service;

/** Redis sorted set = the interview's ordered set + rank index: ZINCRBY, ZREVRANK, ZREVRANGE are all O(log n). */
@Service
public class LeaderboardService {
    public record Entry(long rank, String player, long score) {}
    public record Standing(Entry you, List<Entry> neighbours, long players) {}

    private final StringRedisTemplate redis;

    public LeaderboardService(StringRedisTemplate redis) { this.redis = redis; }

    public long addPoints(String board, String player, long points) {
        return redis.opsForZSet().incrementScore(key(board), player, points).longValue();
    }

    public List<Entry> top(String board, int n) { return range(board, 0, n - 1); }

    /** Your rank plus `around` players above and below you: the "you are #4,812" screen. */
    public Standing standing(String board, String player, int around) {
        Long idx = redis.opsForZSet().reverseRank(key(board), player);
        if (idx == null) return null;
        List<Entry> window = range(board, Math.max(0, idx - around), idx + around);
        Entry you = window.stream().filter(e -> e.player().equals(player)).findFirst().orElseThrow();
        return new Standing(you, window, redis.opsForZSet().zCard(key(board)));
    }

    private List<Entry> range(String board, long from, long to) {
        Set<TypedTuple<String>> tuples = redis.opsForZSet().reverseRangeWithScores(key(board), from, to);
        if (tuples == null) return List.of();
        long rank = from + 1;
        var out = new java.util.ArrayList<Entry>();
        for (TypedTuple<String> t : tuples) out.add(new Entry(rank++, t.getValue(), t.getScore().longValue()));
        return out;
    }

    private static String key(String board) { return "lb:" + board; }
}
