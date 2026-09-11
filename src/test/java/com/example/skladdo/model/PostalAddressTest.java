package com.example.skladdo.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The compose/parse pair behind structured addresses. {@code parseLegacy} also states the rule the SQL in
 * {@code SchemaMigrations#splitSingleLineAddresses} implements, so the two stay describable together.
 */
class PostalAddressTest {

    @Test
    void composesTheFullAddressAsOneLine() {
        assertThat(PostalAddress.compose("Tartu mnt 5", "10117", "Tallinn"))
                .isEqualTo("Tartu mnt 5, 10117 Tallinn");
    }

    @Test
    void skipsMissingPartsWithoutLeavingStraySeparators() {
        assertThat(PostalAddress.compose("Tartu mnt 5", null, "Tallinn")).isEqualTo("Tartu mnt 5, Tallinn");
        assertThat(PostalAddress.compose("Tartu mnt 5", null, null)).isEqualTo("Tartu mnt 5");
        assertThat(PostalAddress.compose(null, "10117", "Tallinn")).isEqualTo("10117 Tallinn");
        assertThat(PostalAddress.compose("  ", "  ", "Tallinn")).isEqualTo("Tallinn");
    }

    @Test
    void composesToNullWhenNothingIsKnown() {
        // Null rather than "" so callers can keep testing for an address with a plain null check.
        assertThat(PostalAddress.compose(null, null, null)).isNull();
        assertThat(PostalAddress.compose("", "  ", null)).isNull();
    }

    @Test
    void splitsALegacyLineOnItsLastComma() {
        assertThat(PostalAddress.parseLegacy("Ravi 18, 10138 Tallinn"))
                .isEqualTo(new PostalAddress.Split("Ravi 18", "10138 Tallinn"));
        // The *last* comma, so a two-part street stays with the street.
        assertThat(PostalAddress.parseLegacy("Ravi 18, korpus B, Tallinn"))
                .isEqualTo(new PostalAddress.Split("Ravi 18, korpus B", "Tallinn"));
    }

    @Test
    void leavesTheCityUnknownWhenTheLineCannotBeDividedHonestly() {
        // No comma: it all becomes the street, and the caller decides whether half an address is usable.
        assertThat(PostalAddress.parseLegacy("Somewhere with no comma"))
                .isEqualTo(new PostalAddress.Split("Somewhere with no comma", null));
        // A trailing or leading comma divides nothing.
        assertThat(PostalAddress.parseLegacy("Ravi 18,"))
                .isEqualTo(new PostalAddress.Split("Ravi 18,", null));
        assertThat(PostalAddress.parseLegacy(", Tallinn"))
                .isEqualTo(new PostalAddress.Split(", Tallinn", null));
    }

    @Test
    void handlesNothingAtAll() {
        assertThat(PostalAddress.parseLegacy(null)).isEqualTo(new PostalAddress.Split(null, null));
        assertThat(PostalAddress.parseLegacy("   ")).isEqualTo(new PostalAddress.Split(null, null));
    }

    @Test
    void composeAndParseRoundTripForTheShapeTheMigrationProduces() {
        String composed = PostalAddress.compose("Ravi 18", "10138", "Tallinn");
        PostalAddress.Split split = PostalAddress.parseLegacy(composed);
        // The postal code rides with the city, which is exactly how the migration leaves an old record:
        // legible, and honest about the fact the code was never stored separately.
        assertThat(split.street()).isEqualTo("Ravi 18");
        assertThat(split.city()).isEqualTo("10138 Tallinn");
    }
}
