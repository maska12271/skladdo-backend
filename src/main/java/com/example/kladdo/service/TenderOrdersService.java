package com.example.kladdo.service;

import com.example.kladdo.dto.TenderOrdersDto;
import com.example.kladdo.dto.TenderOrdersDto.OrderRow;
import com.example.kladdo.model.PurchaseOrder;
import com.example.kladdo.model.SalesOrder;
import com.example.kladdo.repository.PurchaseOrderRepository;
import com.example.kladdo.repository.SalesOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Summarises the orders linked to a tender: revenue (sales) and spending (purchases) rolled up in the
 * company base currency, plus the combined order list. Each order is converted via its own snapshotted
 * exchange rate ({@code base = total / rate}), so a tender that mixes currencies still totals correctly.
 */
@Service
public class TenderOrdersService {

    private final SalesOrderRepository salesOrderRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final ExchangeRateService exchangeRateService;

    public TenderOrdersService(SalesOrderRepository salesOrderRepository,
                               PurchaseOrderRepository purchaseOrderRepository,
                               ExchangeRateService exchangeRateService) {
        this.salesOrderRepository = salesOrderRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.exchangeRateService = exchangeRateService;
    }

    @Transactional(readOnly = true)
    public TenderOrdersDto getForTender(Long tenderId) {
        String base = exchangeRateService.baseCurrency();
        List<OrderRow> rows = new ArrayList<>();
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal spending = BigDecimal.ZERO;

        List<SalesOrder> sales = salesOrderRepository.findByTenderIdOrderByIdDesc(tenderId);
        for (SalesOrder o : sales) {
            BigDecimal baseTotal = toBase(o.getTotalAmount(), o.getExchangeRate());
            revenue = revenue.add(baseTotal);
            rows.add(new OrderRow(o.getId(), "SALES", o.getOrderNumber(),
                    o.getClient() != null ? o.getClient().getName() : null,
                    o.getStatus() != null ? o.getStatus().name() : null,
                    o.getOrderDate(), nz(o.getTotalAmount()), o.getCurrency(), baseTotal));
        }

        List<PurchaseOrder> purchases = purchaseOrderRepository.findByTenderIdOrderByIdDesc(tenderId);
        for (PurchaseOrder o : purchases) {
            BigDecimal baseTotal = toBase(o.getTotalAmount(), o.getExchangeRate());
            spending = spending.add(baseTotal);
            rows.add(new OrderRow(o.getId(), "PURCHASE", o.getOrderNumber(),
                    o.getManufacturer() != null ? o.getManufacturer().getName() : null,
                    o.getStatus() != null ? o.getStatus().name() : null,
                    o.getOrderDate(), nz(o.getTotalAmount()), o.getCurrency(), baseTotal));
        }

        rows.sort(Comparator.comparing(OrderRow::orderDate, Comparator.nullsLast(Comparator.reverseOrder())));

        return new TenderOrdersDto(base, revenue, spending, revenue.subtract(spending),
                sales.size(), purchases.size(), rows);
    }

    /** Company-currency value of an order total, using the order's snapshotted rate (base = total / rate). */
    private static BigDecimal toBase(BigDecimal total, BigDecimal rate) {
        BigDecimal amount = nz(total);
        if (rate == null || rate.signum() <= 0) {
            return amount.setScale(2, RoundingMode.HALF_UP);
        }
        return amount.divide(rate, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
