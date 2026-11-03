package com.sdp.ci.p06geo;

import java.util.List;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoSearchCommandArgs;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis GEO is a sorted set scored by a 52-bit interleaved geohash: exactly the interview structure.
 * GEOSEARCH scans the centre cell + neighbours and filters by true (haversine) distance for you.
 */
@Service
public class RestaurantGeoService {
    public record Nearby(String id, double km, String geohash) {}

    static final String KEY = "geo:restaurants";
    private final StringRedisTemplate redis;

    public RestaurantGeoService(StringRedisTemplate redis) { this.redis = redis; }

    public void upsert(String id, double lat, double lon) {
        redis.opsForGeo().add(KEY, new Point(lon, lat), id);          // note: Redis takes (longitude, latitude)
    }

    public List<Nearby> near(double lat, double lon, double radiusKm, int limit) {
        var results = redis.opsForGeo().search(KEY,
                GeoReference.fromCoordinate(lon, lat),
                new Distance(radiusKm, Metrics.KILOMETERS),
                GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().sortAscending().limit(limit));
        if (results == null) return List.of();
        List<String> ids = results.getContent().stream().map(r -> r.getContent().getName()).toList();
        List<String> hashes = ids.isEmpty() ? List.of() : redis.opsForGeo().hash(KEY, ids.toArray(String[]::new));
        return java.util.stream.IntStream.range(0, ids.size())
                .mapToObj(i -> new Nearby(ids.get(i), Math.round(results.getContent().get(i).getDistance().getValue() * 100) / 100.0, hashes.get(i)))
                .toList();
    }
}
