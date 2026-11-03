package com.sdp.ci.p08leaderboard;

import com.sdp.ci.common.Problems;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/leaderboards/{board}")
public class LeaderboardController {
    public record ScoreRequest(@NotBlank String player, @Positive long points) {}
    public record ScoreResponse(String player, long score) {}

    private final LeaderboardService leaderboard;

    public LeaderboardController(LeaderboardService leaderboard) { this.leaderboard = leaderboard; }

    @PostMapping("/scores")
    public ScoreResponse add(@PathVariable String board, @Valid @RequestBody ScoreRequest req) {
        return new ScoreResponse(req.player(), leaderboard.addPoints(board, req.player(), req.points()));
    }

    @GetMapping("/top")
    public List<LeaderboardService.Entry> top(@PathVariable String board, @RequestParam(defaultValue = "10") int n) {
        return leaderboard.top(board, Math.min(n, 100));
    }

    @GetMapping("/players/{player}")
    public LeaderboardService.Standing standing(@PathVariable String board, @PathVariable String player) {
        var s = leaderboard.standing(board, player, 2);
        if (s == null) throw Problems.of(HttpStatus.NOT_FOUND, player + " has no score on " + board);
        return s;
    }
}
