package com.example.skladdo.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Helpers for the structured postal addresses carried by {@link Client}, {@link CompanySettings} and the
 * buyer snapshot on {@link Invoice}.
 *
 * <p>The parts are stored separately because the Estonian e-invoice schema requires the street line and
 * the city as a mandatory <em>pair</em> - a single free-text line cannot be split back apart reliably, and
 * guessing produces documents receivers reject. Everything that just wants something to print (the invoice
 * PDF, email tokens, a delivery address copied from a client) goes through {@link #compose} instead, so
 * one stored shape still serves both.</p>
 */
public final class PostalAddress {

    private PostalAddress() {
    }

    /**
     * The parts as a single display line, e.g. {@code "Tartu mnt 5, 10117 Tallinn"}. Missing parts are
     * skipped rather than leaving stray separators, and an entirely empty address returns {@code null} so
     * callers can keep testing for one with a null check.
     */
    public static String compose(String street, String postalCode, String city) {
        List<String> parts = new ArrayList<>();
        if (isPresent(street)) {
            parts.add(street.trim());
        }
        // Postal code and city belong on one line, in that order, as they are written on an envelope.
        String cityLine = joinCityLine(postalCode, city);
        if (cityLine != null) {
            parts.add(cityLine);
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    /** {@code "10117 Tallinn"}, or whichever half is present, or {@code null}. */
    private static String joinCityLine(String postalCode, String city) {
        boolean hasCode = isPresent(postalCode);
        boolean hasCity = isPresent(city);
        if (hasCode && hasCity) {
            return postalCode.trim() + " " + city.trim();
        }
        if (hasCity) {
            return city.trim();
        }
        return hasCode ? postalCode.trim() : null;
    }

    /**
     * Best-effort split of a legacy single-line address into a street part and a city part, used to
     * migrate records written before the parts were stored separately, and to salvage the buyer snapshot
     * on invoices issued back then.
     *
     * <p>The last comma is the boundary, so {@code "Ravi 18, 10138 Tallinn"} splits as expected. A line
     * with no comma cannot be divided honestly, so it all becomes the street and the city comes back
     * {@code null} - the caller decides whether a half-known address is usable.</p>
     */
    public static Split parseLegacy(String address) {
        if (!isPresent(address)) {
            return new Split(null, null);
        }
        String trimmed = address.trim();
        int boundary = trimmed.lastIndexOf(',');
        if (boundary <= 0 || boundary == trimmed.length() - 1) {
            return new Split(trimmed, null);
        }
        String street = trimmed.substring(0, boundary).trim();
        String city = trimmed.substring(boundary + 1).trim();
        return street.isEmpty() || city.isEmpty() ? new Split(trimmed, null) : new Split(street, city);
    }

    /** A legacy address divided into the street line and the city, either of which may be {@code null}. */
    public record Split(String street, String city) {
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
