package com.example.skladdo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * What the "Issue credit note" dialog captures.
 *
 * <p>A credit note may reverse the whole invoice or only part of it - two of five items came back, so
 * three are still genuinely owed. {@code lines} names what is being credited and in what quantity.</p>
 *
 * <p>Absent (null) means the whole invoice. An empty list is <em>not</em> the same thing: it means every
 * quantity was set to zero, which reverses nothing and is rejected. Conflating the two would turn "credit
 * nothing" into "credit everything" - the single most costly mistake this endpoint could make.</p>
 *
 * <p>{@code reason} is required rather than optional: a credit note is a correction that stays in the
 * books permanently, and one with no stated cause is of little use to whoever reads them later.</p>
 */
public record CreateCreditNoteRequest(
        LocalDate issueDate,
        @NotBlank @Size(max = 2000) String reason,
        @Valid List<CreditLine> lines,
        /** Whether the delivery charge is credited too. Defaults to true when crediting everything. */
        Boolean creditDelivery
) {

    /** One invoice line being credited, in whole or in part. */
    public record CreditLine(
            @NotNull Long invoiceItemId,
            @NotNull @Min(1) Integer quantity
    ) {
    }

    /**
     * Whether the caller named specific lines, as opposed to omitting the field and asking for the whole
     * invoice. An empty list counts as naming lines - see the note above on why that distinction matters.
     */
    public boolean isPartial() {
        return lines != null;
    }
}
