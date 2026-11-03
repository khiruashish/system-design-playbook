package com.sdp.ci.p06geo;

import static org.assertj.core.api.Assertions.assertThat;

import com.sdp.ci.support.RedisIntegrationTest;
import org.junit.jupiter.api.Test;

class GeoIT extends RedisIntegrationTest {

    @Test
    void restaurantsNearMeWithRedisGeo() {
        var t = transcript("GeohashSpringBoot");
        String[][] places = {
                {"truffles-koramangala", "12.9333", "77.6146"},
                {"meghana-residency-rd", "12.9682", "77.6070"},
                {"vidyarthi-bhavan", "12.9452", "77.5713"},
                {"mtr-lalbagh", "12.9553", "77.5854"},
                {"toit-indiranagar", "12.9791", "77.6408"},
                {"airport-food-court", "13.1986", "77.7066"}};
        for (String[] p : places) {
            t.put("/api/v1/restaurants/" + p[0] + "/location", "{\"lat\":" + p[1] + ",\"lon\":" + p[2] + "}");
        }
        var near = t.get("/api/v1/restaurants/near?lat=12.9716&lon=77.5946&km=3&limit=5");   // MG Road
        assertThat(near.status()).isEqualTo(200);
        assertThat(near.body()).contains("meghana-residency-rd", "mtr-lalbagh").doesNotContain("airport-food-court");
        assertThat(near.body().indexOf("meghana-residency-rd")).isLessThan(near.body().indexOf("mtr-lalbagh"));   // sorted by distance
        assertThat(t.get("/api/v1/restaurants/near?lat=95&lon=77.5").status()).isEqualTo(400);
        t.pass();
    }
}
