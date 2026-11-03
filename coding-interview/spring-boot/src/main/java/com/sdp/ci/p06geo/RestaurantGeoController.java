package com.sdp.ci.p06geo;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/restaurants")
public class RestaurantGeoController {
    public record Location(@DecimalMin("-90") @DecimalMax("90") double lat, @DecimalMin("-180") @DecimalMax("180") double lon) {}

    private final RestaurantGeoService geo;

    public RestaurantGeoController(RestaurantGeoService geo) { this.geo = geo; }

    @PutMapping("/{id}/location")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void move(@PathVariable String id, @RequestBody @jakarta.validation.Valid Location loc) { geo.upsert(id, loc.lat(), loc.lon()); }

    @GetMapping("/near")
    public List<RestaurantGeoService.Nearby> near(@RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
                                                  @RequestParam @DecimalMin("-180") @DecimalMax("180") double lon,
                                                  @RequestParam(defaultValue = "3") @Positive @Max(50) double km,
                                                  @RequestParam(defaultValue = "10") @Positive @Max(100) int limit) {
        return geo.near(lat, lon, km, limit);
    }
}
