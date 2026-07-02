package com.example.kladdo.service;

import com.example.kladdo.dto.AddressSuggestionDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Worldwide address typeahead backed by Photon (<a href="https://photon.komoot.io">photon.komoot.io</a>),
 * a free, key-less geocoder built on OpenStreetMap data — so suggestions work for any country, not just
 * Estonia. The call is proxied through the backend (rather than hit from the browser) to avoid CORS and
 * keep the provider swappable in one place behind {@link AddressSuggestionDto}. Network/parse failures
 * yield an empty list rather than an error, so a flaky lookup never blocks form entry.
 */
@Service
public class AddressLookupService {

    private static final Logger log = LoggerFactory.getLogger(AddressLookupService.class);

    private static final String ENDPOINT = "https://photon.komoot.io/api/";
    private static final int MAX_RESULTS = 8;
    private static final int MIN_QUERY_LENGTH = 3;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    // Self-contained instance: the app doesn't expose an ObjectMapper bean, and ObjectMapper is
    // thread-safe for reads once built, so a single shared instance is fine here.
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Up to {@value #MAX_RESULTS} address suggestions for the partial text, or an empty list. */
    public List<AddressSuggestionDto> suggest(String query) {
        if (query == null || query.trim().length() < MIN_QUERY_LENGTH) {
            return List.of();
        }
        try {
            String url = ENDPOINT
                    + "?q=" + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)
                    + "&limit=" + MAX_RESULTS;
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(4))
                    .header("Accept", "application/json")
                    .header("User-Agent", "Kladdo/1.0 (address autocomplete)")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return List.of();
            }
            JsonNode features = objectMapper.readTree(response.body()).path("features");
            List<AddressSuggestionDto> out = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>(); // drop duplicate address strings
            for (JsonNode feature : features) {
                JsonNode p = feature.path("properties");
                String address = formatAddress(p);
                if (address == null || !seen.add(address)) {
                    continue;
                }
                out.add(new AddressSuggestionDto(address, blankToNull(p.path("postcode").asText(""))));
            }
            return out;
        } catch (Exception e) {
            log.debug("Address lookup failed for '{}': {}", query, e.toString());
            return List.of();
        }
    }

    /**
     * Builds a single human-readable line from a Photon feature's properties, e.g.
     * "Tartu maantee 1, 10145 Tallinn, Estonia". Returns {@code null} when there is nothing usable.
     */
    private static String formatAddress(JsonNode p) {
        String street = p.path("street").asText("");
        String houseNumber = p.path("housenumber").asText("");
        String name = p.path("name").asText("");

        String line1;
        if (!street.isBlank()) {
            line1 = houseNumber.isBlank() ? street : street + " " + houseNumber;
        } else {
            line1 = name; // POI / place with no street (e.g. a town or landmark)
        }

        String city = firstNonBlank(
                p.path("city").asText(""),
                p.path("district").asText(""),
                p.path("county").asText(""),
                p.path("state").asText(""));
        String postcode = p.path("postcode").asText("");
        String country = p.path("country").asText("");

        List<String> parts = new ArrayList<>();
        if (line1 != null && !line1.isBlank()) {
            parts.add(line1.trim());
        }
        String cityLine = !postcode.isBlank() && city != null && !city.isBlank()
                ? postcode + " " + city
                : (city != null && !city.isBlank() ? city : postcode);
        if (cityLine != null && !cityLine.isBlank()) {
            parts.add(cityLine.trim());
        }
        if (!country.isBlank()) {
            parts.add(country.trim());
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
