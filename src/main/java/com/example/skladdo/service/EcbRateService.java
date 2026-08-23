package com.example.skladdo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Supplies exchange rates from the European Central Bank's free, key-less daily reference feed
 * (<a href="https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml">eurofxref-daily.xml</a>). The
 * feed quotes ~30 currencies as units per 1 EUR; any base-to-foreign rate is derived by pivoting through
 * the euro. The parsed snapshot is cached in memory and refreshed at most a few times a day; a network or
 * parse failure keeps the last good snapshot (or stays empty), so a flaky feed never blocks form entry -
 * callers then fall back to the last-used rate (see {@link ExchangeRateService}).
 *
 * <p>Note: the ECB does not publish every currency (it dropped the Russian ruble in 2022), so
 * {@link #quote(String, String)} returns empty whenever either side is not in the feed.</p>
 */
@Service
public class EcbRateService {

    private static final Logger log = LoggerFactory.getLogger(EcbRateService.class);

    private static final String ENDPOINT = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml";
    private static final Duration CACHE_TTL = Duration.ofHours(6);
    private static final int RATE_SCALE = 6;

    private static final Pattern TIME_PATTERN = Pattern.compile("time=['\"](\\d{4}-\\d{2}-\\d{2})['\"]");
    private static final Pattern RATE_PATTERN =
            Pattern.compile("currency=['\"]([A-Z]{3})['\"]\\s+rate=['\"]([0-9.]+)['\"]");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private volatile Snapshot cache;
    private volatile Instant fetchedAt;

    /** A dated set of ECB rates: {@code ratesPerEur} maps an ISO code to how many of it 1 EUR buys. */
    public record Snapshot(LocalDate date, Map<String, BigDecimal> ratesPerEur) {
    }

    /** A resolved rate with the ECB publication date it came from. */
    public record Rate(BigDecimal rate, LocalDate asOfDate) {
    }

    /**
     * How many units of {@code foreign} one unit of {@code base} buys ({@code 1 base = rate foreign}),
     * from the latest ECB snapshot, or empty when either currency is not published by the ECB (or the feed
     * is unavailable). Same currency both sides yields 1.
     */
    public Optional<Rate> quote(String base, String foreign) {
        return pivot(current(), base, foreign);
    }

    /**
     * Derives {@code 1 base = rate foreign} from an ECB snapshot by pivoting through the euro, or empty
     * when either currency is absent (or the snapshot is null). Static and side-effect-free for testing.
     */
    static Optional<Rate> pivot(Snapshot snap, String base, String foreign) {
        if (snap == null) {
            return Optional.empty();
        }
        String b = normalize(base);
        String f = normalize(foreign);
        if (b == null || f == null) {
            return Optional.empty();
        }
        if (b.equals(f)) {
            return Optional.of(new Rate(BigDecimal.ONE, snap.date()));
        }
        BigDecimal eurBase = snap.ratesPerEur().get(b);
        BigDecimal eurForeign = snap.ratesPerEur().get(f);
        if (eurBase == null || eurForeign == null || eurBase.signum() <= 0) {
            return Optional.empty();
        }
        // rate(base->foreign) = (foreign per EUR) / (base per EUR).
        BigDecimal rate = eurForeign.divide(eurBase, RATE_SCALE, RoundingMode.HALF_UP);
        return Optional.of(new Rate(rate, snap.date()));
    }

    /**
     * Refreshes the cached snapshot if it is missing or stale. Called by the scheduled warm-up so the
     * first user of the day does not pay the feed's latency inline. Never throws.
     */
    public void warmCache() {
        current();
    }

    /** The cached snapshot, refreshing it when missing or stale. Never throws. */
    private Snapshot current() {
        Snapshot snap = cache;
        boolean stale = fetchedAt == null || Duration.between(fetchedAt, Instant.now()).compareTo(CACHE_TTL) > 0;
        if (snap == null || stale) {
            refresh();
        }
        return cache;
    }

    private synchronized void refresh() {
        // Another thread may have refreshed while we waited on the lock.
        if (cache != null && fetchedAt != null
                && Duration.between(fetchedAt, Instant.now()).compareTo(CACHE_TTL) <= 0) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/xml")
                    .header("User-Agent", "Skladdo/1.0 (currency rates)")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                parse(response.body()).ifPresent(snap -> {
                    cache = snap;
                    fetchedAt = Instant.now();
                });
            }
        } catch (Exception e) {
            // Keep whatever we had; callers fall back to the last-used rate.
            log.debug("ECB rate refresh failed: {}", e.toString());
            // Back off so we don't refetch on every request while the feed is down.
            if (fetchedAt == null) {
                fetchedAt = Instant.now();
            }
        }
    }

    /**
     * Parses an ECB {@code eurofxref-daily.xml} document into a dated snapshot (EUR itself included at 1).
     * Empty when the document has no usable rates. Static and side-effect-free so it is trivial to test.
     */
    static Optional<Snapshot> parse(String xml) {
        if (xml == null || xml.isBlank()) {
            return Optional.empty();
        }
        Matcher rateMatcher = RATE_PATTERN.matcher(xml);
        Map<String, BigDecimal> rates = new HashMap<>();
        while (rateMatcher.find()) {
            try {
                rates.put(rateMatcher.group(1), new BigDecimal(rateMatcher.group(2)));
            } catch (NumberFormatException ignored) {
                // Skip a malformed rate rather than failing the whole snapshot.
            }
        }
        if (rates.isEmpty()) {
            return Optional.empty();
        }
        rates.put("EUR", BigDecimal.ONE);
        Matcher timeMatcher = TIME_PATTERN.matcher(xml);
        LocalDate date = timeMatcher.find() ? LocalDate.parse(timeMatcher.group(1)) : LocalDate.now();
        return Optional.of(new Snapshot(date, rates));
    }

    private static String normalize(String code) {
        return code == null || code.isBlank() ? null : code.trim().toUpperCase();
    }
}
