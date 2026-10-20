// ContractL2.java — run with: java ContractL2.java   (JDK 21, no dependencies)
// CONSUMER-DRIVEN CONTRACT TESTING from scratch (the idea behind Pact). Integration tests that spin up every service are
// slow and flaky; contract tests let each side be tested ALONE against a shared CONTRACT. The CONSUMER (a checkout
// service) declares exactly the request it sends and the response FIELDS it depends on. That contract is published. The
// PROVIDER (the cart service) is then verified against the contract WITHOUT the consumer present: we replay the
// consumer's request at the real provider and assert the response satisfies every expectation. When the provider later
// drops/renames a field the consumer needs, verification FAILS — catching the break at build time, not in production.
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.regex.*;

public class ContractL2 {

    // ---- the contract the CONSUMER publishes: request + the fields it relies on ----
    record Expectation(String method, String path, Map<String,String> requiredFields) {}

    // The checkout consumer only reads cart_id, total_paise and currency from the cart response. It does NOT care about
    // any other field. That is the whole point: the contract is the consumer's ACTUAL needs, nothing more.
    static final Expectation CONTRACT = new Expectation("GET", "/cart/42",
            Map.of("cart_id", "number", "total_paise", "number", "currency", "string"));

    // ---- two versions of the PROVIDER ----
    // v1 honours the contract. v2 has "refactored" total_paise -> amount and dropped currency — a breaking change the
    // provider team thought was internal. Contract verification is what tells them it is NOT internal.
    static HttpServer startProvider(int port, boolean v2) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        s.createContext("/cart/42", ex -> {
            String body = v2
                ? "{\"cart_id\": 42, \"amount\": 129900, \"items\": 3}"                       // renamed + dropped currency
                : "{\"cart_id\": 42, \"total_paise\": 129900, \"currency\": \"INR\", \"items\": 3}";
            byte[] b = body.getBytes();
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        s.start();
        return s;
    }

    // ---- the verifier: replay the contract's request at a provider and check every required field is present ----
    record Result(boolean ok, List<String> failures) {}
    static Result verify(int port) throws Exception {
        HttpClient c = HttpClient.newHttpClient();
        HttpResponse<String> r = c.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + CONTRACT.path())).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        String body = r.body();
        List<String> failures = new ArrayList<>();
        for (var e : CONTRACT.requiredFields().entrySet()) {
            String field = e.getKey(), type = e.getValue();
            if (!hasField(body, field, type))
                failures.add("missing/!" + type + " field '" + field + "'");
        }
        return new Result(failures.isEmpty(), failures);
    }

    // minimal JSON field presence + coarse type check (no dependency); enough to demonstrate the contract mechanic
    static boolean hasField(String json, String field, String type) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*(\"[^\"]*\"|-?\\d+(?:\\.\\d+)?)").matcher(json);
        if (!m.find()) return false;
        String val = m.group(1);
        boolean isString = val.startsWith("\"");
        return type.equals("string") == isString;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Consumer-driven contract: the checkout consumer needs {cart_id:number, total_paise:number, currency:string}.");
        System.out.println("We verify each provider version against that contract, with NO consumer running — just the published contract.\n");
        System.out.println("   Contract (published by the consumer): GET /cart/42 must return " + CONTRACT.requiredFields());
        System.out.println();

        HttpServer p1 = startProvider(19960, false);
        Result r1 = verify(19960);
        System.out.printf("   provider v1 (original)                : %s%n", r1.ok() ? "VERIFIED ✓ — honours the contract" : "FAILED ✗ " + r1.failures());
        p1.stop(0);

        HttpServer p2 = startProvider(19961, true);
        Result r2 = verify(19961);
        System.out.printf("   provider v2 (renamed total_paise→amount, dropped currency): %s%n",
                r2.ok() ? "VERIFIED ✓" : "CONTRACT BROKEN ✗ — " + r2.failures());
        p2.stop(0);

        System.out.println("\n   → v1 passes: every field the consumer relies on is present with the right type. v2 'refactored' a field the");
        System.out.println("     provider team believed was internal — and the contract test FAILS the provider's build, naming the exact");
        System.out.println("     missing fields, before it ever reaches the consumer. That is how you decouple deploys safely: the provider");
        System.out.println("     may change anything the contract does NOT mention (it changed nothing about 'items'), but not what it does.");
    }
}
