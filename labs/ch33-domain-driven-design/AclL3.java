// AclL3.java — run with: java AclL3.java legacy_erp_feed.txt   (JDK 21, no dependencies)
// ANTI-CORRUPTION LAYER (ACL). The Catalog bounded context must ingest the legacy ERP's item master. The ERP speaks its own
// language: pipe-delimited rows, unit codes like GM/GMS/LTR/NOS, prices as rupee decimals, status codes S1..S9, HSN tax codes
// that are sometimes blank. Two ways to integrate:
//   NAIVE — map ERP fields straight onto the domain with a few conversions inline ("it's just a DTO").
//   ACL   — a translation layer that converts ERP concepts into the Catalog's OWN ubiquitous language (Quantity, Money,
//           Availability, TaxCategory), validates them, and QUARANTINES anything it cannot translate faithfully.
// We run the same 12 real-looking rows through both and count how many produce a wrong Product inside the Catalog domain.
import java.math.*;
import java.nio.file.*;
import java.util.*;

public class AclL3 {
    // ---------- the Catalog context's own model (its ubiquitous language) ----------
    enum Unit { G, KG, ML, L, PIECE }
    enum Availability { SELLABLE, SEASONAL, DISCONTINUED }
    record Quantity(BigDecimal value, Unit unit) {}
    record Money(long paise) { public String toString() { return String.format("₹%d.%02d", paise / 100, paise % 100); } }
    record Product(String sku, String name, String unit, long pricePaise, boolean sellable, String taxCategory) {}

    // ---------- NAIVE: the legacy row leaks straight into the domain ----------
    static Product naive(String[] f) {
        return new Product("SKU-" + f[0], f[1],
                f[2].toLowerCase(),                                   // legacy code used as-is ("gm", "ltr", "nos")
                (long) (Double.parseDouble(f[3]) * 100),              // float math + truncation
                !f[4].isBlank(),                                      // "has a status" == "can be sold"
                f.length > 5 && !f[5].isBlank() ? "HSN-" + f[5] : "EXEMPT");   // blank HSN silently becomes tax-exempt
    }

    // ---------- ACL: translate into the domain language, or quarantine with a reason ----------
    static final Map<String, Unit> UOM = Map.of("GM", Unit.G, "GMS", Unit.G, "KG", Unit.KG, "ML", Unit.ML,
            "LTR", Unit.L, "LT", Unit.L, "NOS", Unit.PIECE, "PCS", Unit.PIECE);
    static final Map<String, Availability> STATUS = Map.of("S1", Availability.SELLABLE, "S7", Availability.SEASONAL,
            "S3", Availability.DISCONTINUED);
    record Translation(Product product, String quarantineReason) {}

    static Translation acl(String[] f) {
        Unit unit = UOM.get(f[2]);
        if (unit == null) return new Translation(null, "unknown unit of measure '" + f[2] + "'");
        Availability avail = STATUS.get(f[4]);
        if (avail == null) return new Translation(null, "unknown status code '" + f[4] + "'");
        String hsn = f.length > 5 ? f[5].trim() : "";
        if (hsn.isEmpty()) return new Translation(null, "missing HSN code — tax category unknown, cannot invoice (GST)");
        Money price = new Money(new BigDecimal(f[3]).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact());
        if (price.paise() < 100) return new Translation(null, "implausible price " + price + " for a packaged item (likely entry error)");
        String name = titleCase(f[1]);
        return new Translation(new Product("SKU-" + f[0], name, unit.name().toLowerCase(), price.paise(),
                avail != Availability.DISCONTINUED, "HSN-" + hsn), null);
    }
    static String titleCase(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : s.toLowerCase().split(" ")) if (!w.isEmpty()) b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        return b.toString().trim();
    }

    public static void main(String[] args) throws Exception {
        List<String[]> rows = Files.readAllLines(Path.of(args[0])).stream()
                .filter(l -> !l.isBlank() && !l.startsWith("#")).map(l -> l.split("\\|", -1)).toList();
        System.out.printf("Ingesting %d legacy ERP rows into the Catalog context, naive mapping vs anti-corruption layer.%n%n", rows.size());
        System.out.printf("   %-26s | %-44s | %s%n", "ERP row", "naive: defect in the domain", "ACL");
        int naiveDefects = 0, accepted = 0, quarantined = 0;
        for (String[] f : rows) {
            Product n = naive(f);
            Translation t = acl(f);
            List<String> defects = new ArrayList<>();
            if (t.product() == null) {
                if (n.sellable()) defects.add("admitted as sellable");
            } else {
                Product good = t.product();
                if (n.pricePaise() != good.pricePaise()) defects.add("price " + new Money(n.pricePaise()) + " ≠ " + new Money(good.pricePaise()));
                if (!n.unit().equals(good.unit())) defects.add("unit '" + n.unit() + "' ≠ '" + good.unit() + "'");
                if (n.sellable() != good.sellable()) defects.add("sellable=" + n.sellable() + " (is " + (good.sellable() ? "sellable" : "discontinued") + ")");
            }
            if (!defects.isEmpty()) naiveDefects++;
            String aclCol;
            if (t.product() == null) { quarantined++; aclCol = "QUARANTINED: " + t.quarantineReason(); }
            else { accepted++; aclCol = "ok: " + t.product().name() + ", " + new Money(t.product().pricePaise()) + ", size unit " + t.product().unit()
                    + (t.product().sellable() ? "" : ", NOT sellable"); }
            System.out.printf("   %-26s | %-44s | %s%n", f[0] + " " + trim(f[1], 18), defects.isEmpty() ? "—" : String.join("; ", defects), aclCol);
        }
        System.out.printf("%n   Rows producing a WRONG product inside the Catalog domain:  naive %d/%d   ACL 0/%d (%d translated, %d quarantined with a reason)%n%n",
                naiveDefects, rows.size(), rows.size(), accepted, quarantined);
        System.out.println("   → the naive mapping 'works' — every row loads — and that is the problem. Legacy unit codes leak in unnormalized, so");
        System.out.println("     a pack-size filter on 'g' or 'l' silently misses products; double arithmetic plus truncation turns ₹78.85 into ₹78.84;");
        System.out.println("     any status code means 'sellable', so a discontinued Pepsi and an unknown S9 onion go on sale; a blank HSN becomes");
        System.out.println("     'tax-exempt' on a GST invoice; and a ₹0.29 data-entry error goes live. The ACL translates into the Catalog's own");
        System.out.println("     language (Unit, Money, Availability), uses exact decimal arithmetic, and quarantines what it can't translate");
        System.out.println("     faithfully — with a reason a human can act on. The ERP's model stays outside the boundary, where it belongs.");
    }
    static String trim(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "…"; }
}
