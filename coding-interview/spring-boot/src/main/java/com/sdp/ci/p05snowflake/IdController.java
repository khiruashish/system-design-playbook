package com.sdp.ci.p05snowflake;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.stream.LongStream;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IdController {
    private final SnowflakeIdGenerator ids;

    public IdController(SnowflakeIdGenerator ids) { this.ids = ids; }

    /** Ids are returned as strings: JavaScript numbers lose precision above 2^53. */
    @GetMapping("/api/v1/ids")
    public List<String> next(@RequestParam(defaultValue = "1") @Min(1) @Max(1000) int count) {
        return LongStream.range(0, count).mapToObj(i -> String.valueOf(ids.nextId())).toList();
    }

    @GetMapping("/api/v1/ids/{id}")
    public SnowflakeIdGenerator.Parts decode(@PathVariable long id) { return ids.decode(id); }
}
