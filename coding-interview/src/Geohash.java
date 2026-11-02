import java.util.*;

/** Problem: "restaurants near me": encode lat/lon so that nearby points share a prefix; search the cell + its 8 neighbours. */
public class Geohash {

    private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";

    /** Interleave longitude and latitude bisection bits (lon first), 5 bits per base32 char. */
    static String encode(double lat, double lon, int precision) {
        double[] latR = {-90, 90}, lonR = {-180, 180};
        StringBuilder hash = new StringBuilder();
        boolean even = true; int bit = 0, ch = 0;
        while (hash.length() < precision) {
            double[] r = even ? lonR : latR;
            double v = even ? lon : lat, mid = (r[0] + r[1]) / 2;
            if (v >= mid) { ch = (ch << 1) | 1; r[0] = mid; } else { ch <<= 1; r[1] = mid; }
            even = !even;
            if (++bit == 5) { hash.append(BASE32.charAt(ch)); bit = 0; ch = 0; }
        }
        return hash.toString();
    }

    /** Decode to the cell's bounding box: [minLat, maxLat, minLon, maxLon]. */
    static double[] bounds(String hash) {
        double[] latR = {-90, 90}, lonR = {-180, 180};
        boolean even = true;
        for (char c : hash.toCharArray()) {
            int cd = BASE32.indexOf(c);
            for (int mask = 16; mask > 0; mask >>= 1) {
                double[] r = even ? lonR : latR;
                double mid = (r[0] + r[1]) / 2;
                if ((cd & mask) != 0) r[0] = mid; else r[1] = mid;
                even = !even;
            }
        }
        return new double[]{latR[0], latR[1], lonR[0], lonR[1]};
    }

    /** Neighbours by stepping one cell height/width from the centre (robust, avoids lookup tables). */
    static Set<String> cellAndNeighbours(String hash) {
        double[] b = bounds(hash);
        double h = b[1] - b[0], w = b[3] - b[2], lat = (b[0] + b[1]) / 2, lon = (b[2] + b[3]) / 2;
        Set<String> out = new TreeSet<>();
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            double nlat = Math.max(-89.999999, Math.min(89.999999, lat + dy * h));
            double nlon = ((lon + dx * w + 540) % 360) - 180;          // wrap the antimeridian
            out.add(encode(nlat, nlon, hash.length()));
        }
        return out;
    }

    static double km(double lat1, double lon1, double lat2, double lon2) {   // haversine
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLon / 2), 2);
        return 6371 * 2 * Math.asin(Math.sqrt(a));
    }

    record Place(String name, double lat, double lon) { }

    public static void main(String[] args) {
        double meLat = 12.9716, meLon = 77.5946;                       // Bengaluru
        List<Place> places = List.of(new Place("Cafe A", 12.9721, 77.5933), new Place("Diner B", 12.9690, 77.6010),
                new Place("Bistro C", 12.9352, 77.6245), new Place("Mumbai D", 19.0760, 72.8777));

        int precision = 6;                                              // ~1.2 km x 0.6 km cells
        String mine = encode(meLat, meLon, precision);
        Set<String> searchCells = cellAndNeighbours(mine);
        System.out.println("my geohash (p=6) = " + mine + ", search cells = " + searchCells);

        Map<String, List<Place>> index = new HashMap<>();             // production: geohash column with a B-tree index, or Redis GEO
        for (Place p : places) index.computeIfAbsent(encode(p.lat(), p.lon(), precision), x -> new ArrayList<>()).add(p);

        List<String> found = new ArrayList<>();
        for (String cell : searchCells)
            for (Place p : index.getOrDefault(cell, List.of()))
                if (km(meLat, meLon, p.lat(), p.lon()) <= 1.0) found.add(String.format("%s (%.2f km)", p.name(), km(meLat, meLon, p.lat(), p.lon())));
        Collections.sort(found);
        System.out.println("within 1 km: " + found);
        System.out.println("shared prefix of two nearby points: " + encode(12.9716, 77.5946, 7) + " vs " + encode(12.9721, 77.5933, 7));
        check(found.size() == 2 && found.get(0).startsWith("Cafe A"), "nearby found, far filtered");
        check(searchCells.size() == 9, "cell + 8 neighbours");
        check(encode(57.64911, 10.40744, 11).equals("u4pruydqqvj"), "matches the reference geohash");
        System.out.println("PASS");
    }

    static void check(boolean ok, String what) { if (!ok) { System.out.println("FAILED: " + what); System.exit(1); } }
}
