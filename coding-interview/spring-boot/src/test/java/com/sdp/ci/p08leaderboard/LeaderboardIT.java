package com.sdp.ci.p08leaderboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.sdp.ci.support.RedisIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class LeaderboardIT extends RedisIntegrationTest {

    @Test
    void ranksTopNAndNeighbours() {
        var t = transcript("LeaderboardSpringBoot");
        String[] players = {"asha", "ravi", "meera", "kabir", "zoya", "dev", "isha", "arjun"};
        long[] points = {1200, 950, 1430, 610, 880, 1010, 770, 1325};
        for (int i = 0; i < players.length; i++) {
            t.quietly(MockMvcRequestBuilders.post("/api/v1/leaderboards/weekly/scores").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"player\":\"" + players[i] + "\",\"points\":" + points[i] + "}"));
        }
        t.note("8 players posted scores");
        var bonus = t.post("/api/v1/leaderboards/weekly/scores", "{\"player\":\"ravi\",\"points\":300}");
        assertThat(bonus.body()).contains("\"score\":1250");

        var top = t.get("/api/v1/leaderboards/weekly/top?n=3");
        assertThat(top.body()).contains("{\"rank\":1,\"player\":\"meera\",\"score\":1430}");
        var me = t.get("/api/v1/leaderboards/weekly/players/zoya");
        assertThat(me.body()).contains("\"you\":{\"rank\":6,\"player\":\"zoya\",\"score\":880}").contains("\"players\":8");
        assertThat(t.get("/api/v1/leaderboards/weekly/players/nobody").status()).isEqualTo(404);
        assertThat(t.post("/api/v1/leaderboards/weekly/scores", "{\"player\":\"\",\"points\":-5}").status()).isEqualTo(400);
        t.pass();
    }
}
