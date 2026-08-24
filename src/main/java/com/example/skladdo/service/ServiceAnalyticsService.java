package com.example.skladdo.service;

import com.example.skladdo.dto.ServiceDetailsDto;
import com.example.skladdo.dto.ServiceDetailsDto.Actor;
import com.example.skladdo.dto.ServiceDetailsDto.AuditInfo;
import com.example.skladdo.dto.ServiceDetailsDto.MonthlyPoint;
import com.example.skladdo.dto.ServiceDetailsDto.OrderLine;
import com.example.skladdo.dto.ServiceDetailsDto.Summary;
import com.example.skladdo.exception.ResourceNotFoundException;
import com.example.skladdo.model.OrderStatus;
import com.example.skladdo.model.SalesOrder;
import com.example.skladdo.model.SalesOrderItem;
import com.example.skladdo.repository.SalesOrderItemRepository;
import com.example.skladdo.repository.ServiceRepository;
import com.example.skladdo.repository.UserRepository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds the aggregated {@link ServiceDetailsDto} for the service detail page. The sales-only
 * counterpart of {@link ProductAnalyticsService}, following the same rules: amounts are converted to
 * the company base currency with each order's own rate, and cancelled orders are still listed but
 * never counted as revenue.
 *
 * <p>The stereotype is fully qualified because {@code com.example.skladdo.model.Service} collides with
 * it - see the note on that entity.</p>
 */
@org.springframework.stereotype.Service
public class ServiceAnalyticsService {

    private final ServiceRepository serviceRepository;
    private final SalesOrderItemRepository salesOrderItemRepository;
    private final UserRepository userRepository;

    public ServiceAnalyticsService(ServiceRepository serviceRepository,
                                   SalesOrderItemRepository salesOrderItemRepository,
                                   UserRepository userRepository) {
        this.serviceRepository = serviceRepository;
        this.salesOrderItemRepository = salesOrderItemRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public ServiceDetailsDto getDetails(Long serviceId) {
        com.example.skladdo.model.Service service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Service not found with id: " + serviceId));

        List<SalesOrderItem> salesItems = salesOrderItemRepository.findByServiceId(serviceId);

        List<OrderLine> salesOrders = new ArrayList<>();
        long totalUnitsSold = 0;
        BigDecimal totalRevenue = BigDecimal.ZERO;
        Set<Long> salesOrderIds = new HashSet<>();
        Map<YearMonth, long[]> units = new TreeMap<>();
        Map<YearMonth, BigDecimal> revenue = new TreeMap<>();

        for (SalesOrderItem item : salesItems) {
            SalesOrder order = item.getSalesOrder();
            int qty = nz(item.getQuantity());
            BigDecimal line = MoneyConverter.toBase(item.getLineTotal(), rateOf(order));

            if (!isCancelled(order)) {
                totalUnitsSold += qty;
                totalRevenue = totalRevenue.add(line);
                if (order != null) salesOrderIds.add(order.getId());

                YearMonth ym = monthOf(order != null ? order.getOrderDate() : null);
                if (ym != null) {
                    units.computeIfAbsent(ym, k -> new long[1])[0] += qty;
                    revenue.merge(ym, line, BigDecimal::add);
                }
            }

            salesOrders.add(new OrderLine(
                    order != null ? order.getId() : null,
                    order != null ? order.getOrderNumber() : null,
                    order != null ? order.getOrderDate() : null,
                    order != null && order.getStatus() != null ? order.getStatus().name() : null,
                    order != null && order.getClient() != null ? order.getClient().getName() : null,
                    qty,
                    MoneyConverter.toBase(item.getUnitPrice(), rateOf(order)),
                    line
            ));
        }

        salesOrders.sort(byDateDesc());

        List<MonthlyPoint> monthly = new ArrayList<>();
        for (Map.Entry<YearMonth, long[]> entry : units.entrySet()) {
            YearMonth ym = entry.getKey();
            monthly.add(new MonthlyPoint(
                    ym.toString(),
                    entry.getValue()[0],
                    revenue.getOrDefault(ym, BigDecimal.ZERO)
            ));
        }

        AuditInfo audit = new AuditInfo(
                actor(service.getCreatedById()),
                service.getCreatedAt(),
                actor(service.getUpdatedById()),
                service.getUpdatedAt()
        );

        return new ServiceDetailsDto(
                audit,
                new Summary(totalUnitsSold, totalRevenue, salesOrderIds.size()),
                monthly,
                salesOrders
        );
    }

    private Actor actor(Long userId) {
        if (userId == null) return null;
        return userRepository.findById(userId)
                .map(u -> new Actor(u.getId(), u.getFullName() != null ? u.getFullName() : u.getEmail()))
                .orElse(null);
    }

    private static Comparator<OrderLine> byDateDesc() {
        return Comparator.comparing(OrderLine::orderDate, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private static YearMonth monthOf(LocalDate date) {
        return date != null ? YearMonth.from(date) : null;
    }

    private static boolean isCancelled(SalesOrder order) {
        return order != null && order.getStatus() == OrderStatus.CANCELLED;
    }

    private static BigDecimal rateOf(SalesOrder order) {
        return order != null ? order.getExchangeRate() : null;
    }

    private static int nz(Integer value) {
        return value != null ? value : 0;
    }
}
