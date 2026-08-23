package com.example.skladdo.service;

import com.example.skladdo.model.PenaltyPeriod;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the one piece of fiddly date math in the invoicing feature. Pure logic, no Spring
 * context - keeps the calculation honest across the four accrual modes and the not-overdue boundary.
 */
class PenaltyCalculatorTest {

    private static final BigDecimal TOTAL = new BigDecimal("1000.00");
    private static final BigDecimal ONE_PERCENT = new BigDecimal("1.0");
    private static final LocalDate DUE = LocalDate.of(2026, 1, 10);

    @Test
    void notOverdueOnOrBeforeDueDate() {
        PenaltyCalculator.Result onTime = PenaltyCalculator.calculate(
                TOTAL, ONE_PERCENT, PenaltyPeriod.DAILY, DUE, DUE);
        assertFalse(onTime.overdue());
        assertEquals(0, onTime.penaltyAmount().compareTo(BigDecimal.ZERO));

        PenaltyCalculator.Result early = PenaltyCalculator.calculate(
                TOTAL, ONE_PERCENT, PenaltyPeriod.DAILY, DUE, DUE.minusDays(3));
        assertFalse(early.overdue());
    }

    @Test
    void dailyAccruesPerDay() {
        // 5 days late, 1% of 1000 = 10/day -> 50.00
        PenaltyCalculator.Result r = PenaltyCalculator.calculate(
                TOTAL, ONE_PERCENT, PenaltyPeriod.DAILY, DUE, DUE.plusDays(5));
        assertTrue(r.overdue());
        assertEquals(5, r.periodsElapsed());
        assertEquals(0, r.penaltyAmount().compareTo(new BigDecimal("50.00")));
    }

    @Test
    void weeklyRoundsPartialWeekUp() {
        // 8 days late -> 2 weeks -> 2 * 10 = 20.00
        PenaltyCalculator.Result r = PenaltyCalculator.calculate(
                TOTAL, ONE_PERCENT, PenaltyPeriod.WEEKLY, DUE, DUE.plusDays(8));
        assertEquals(2, r.periodsElapsed());
        assertEquals(0, r.penaltyAmount().compareTo(new BigDecimal("20.00")));
    }

    @Test
    void monthlyRoundsPartialMonthUp() {
        // 31 days late -> 2 months (30-day months) -> 2 * 10 = 20.00
        PenaltyCalculator.Result r = PenaltyCalculator.calculate(
                TOTAL, ONE_PERCENT, PenaltyPeriod.MONTHLY, DUE, DUE.plusDays(31));
        assertEquals(2, r.periodsElapsed());
        assertEquals(0, r.penaltyAmount().compareTo(new BigDecimal("20.00")));
    }

    @Test
    void oneTimeChargesOnceRegardlessOfDaysLate() {
        PenaltyCalculator.Result r = PenaltyCalculator.calculate(
                TOTAL, ONE_PERCENT, PenaltyPeriod.ONE_TIME, DUE, DUE.plusDays(100));
        assertEquals(1, r.periodsElapsed());
        assertEquals(0, r.penaltyAmount().compareTo(new BigDecimal("10.00")));
    }

    @Test
    void overdueButZeroPenaltyWhenNoPercentConfigured() {
        PenaltyCalculator.Result r = PenaltyCalculator.calculate(
                TOTAL, BigDecimal.ZERO, PenaltyPeriod.DAILY, DUE, DUE.plusDays(10));
        assertTrue(r.overdue());
        assertEquals(0, r.penaltyAmount().compareTo(BigDecimal.ZERO));
    }
}
