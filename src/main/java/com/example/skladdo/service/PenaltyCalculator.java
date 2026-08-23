package com.example.skladdo.service;

import com.example.skladdo.model.PenaltyPeriod;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Pure late-payment penalty math for an invoice. No Spring, no persistence - given the invoice total, the
 * snapshotted penalty terms, the due date and an "as of" date, it returns whether the invoice is overdue
 * and how much penalty has accrued. Kept standalone so the (slightly fiddly) date arithmetic is trivial
 * to unit test.
 *
 * <p>The penalty is a <em>simple</em> (non-compounding) accrual, matching how {@link PenaltyPeriod}'s own
 * documentation describes it: the percentage applies once for {@code ONE_TIME}, and once per elapsed
 * day/week/month otherwise. Weeks are 7 days and months a flat 30 days - a deliberate simplification to
 * avoid calendar-month, day-of-month edge cases.</p>
 */
public final class PenaltyCalculator {

    private PenaltyCalculator() {
    }

    public record Result(boolean overdue, long periodsElapsed, BigDecimal penaltyAmount) {
        public static Result none() {
            return new Result(false, 0, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        }
    }

    /**
     * @param totalAmount   the invoice total the penalty is charged on
     * @param penaltyPercent the penalty rate per period, as a percentage (e.g. {@code 0.5} = 0.5%)
     * @param period        how the penalty accrues
     * @param dueDate       the date payment was due
     * @param asOfDate      the date to evaluate against (today for an unpaid invoice; the paid date when freezing)
     */
    public static Result calculate(BigDecimal totalAmount, BigDecimal penaltyPercent,
                                   PenaltyPeriod period, LocalDate dueDate, LocalDate asOfDate) {
        if (totalAmount == null || penaltyPercent == null || period == null
                || dueDate == null || asOfDate == null) {
            return Result.none();
        }

        long daysLate = ChronoUnit.DAYS.between(dueDate, asOfDate);
        if (daysLate <= 0 || penaltyPercent.signum() <= 0) {
            // Not overdue yet (or no penalty configured) - overdue is reported purely on the date.
            return new Result(daysLate > 0, 0, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        }

        long periods = switch (period) {
            case ONE_TIME -> 1;
            case DAILY -> daysLate;
            case WEEKLY -> ceilDiv(daysLate, 7);
            case MONTHLY -> ceilDiv(daysLate, 30);
        };

        BigDecimal penalty = totalAmount
                .multiply(penaltyPercent)
                .movePointLeft(2)                       // percent -> fraction
                .multiply(BigDecimal.valueOf(periods))
                .setScale(2, RoundingMode.HALF_UP);

        return new Result(true, periods, penalty);
    }

    private static long ceilDiv(long numerator, long denominator) {
        return (numerator + denominator - 1) / denominator;
    }
}
