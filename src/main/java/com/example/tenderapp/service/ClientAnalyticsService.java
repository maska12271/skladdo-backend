package com.example.tenderapp.service;

import com.example.tenderapp.dto.ClientDetailsDto;
import com.example.tenderapp.dto.ClientDetailsDto.OrderLine;
import com.example.tenderapp.exception.ResourceNotFoundException;
import com.example.tenderapp.model.Product;
import com.example.tenderapp.model.SalesOrder;
import com.example.tenderapp.model.SalesOrderItem;
import com.example.tenderapp.repository.ClientRepository;
import com.example.tenderapp.repository.SalesOrderItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the {@link ClientDetailsDto} for the client detail page from the sales-order lines the
 * client (buyer) appears in. Read-only and transactional so lazy associations (order -> product)
 * can be traversed with open-in-view disabled.
 */
@Service
public class ClientAnalyticsService {

    private final ClientRepository clientRepository;
    private final SalesOrderItemRepository salesOrderItemRepository;

    public ClientAnalyticsService(ClientRepository clientRepository,
                                  SalesOrderItemRepository salesOrderItemRepository) {
        this.clientRepository = clientRepository;
        this.salesOrderItemRepository = salesOrderItemRepository;
    }

    @Transactional(readOnly = true)
    public ClientDetailsDto getDetails(Long clientId) {
        if (!clientRepository.existsById(clientId)) {
            throw new ResourceNotFoundException("Client not found with id: " + clientId);
        }

        List<SalesOrderItem> items = salesOrderItemRepository.findBySalesOrder_Client_Id(clientId);
        List<OrderLine> lines = items.stream()
                .map(item -> {
                    SalesOrder order = item.getSalesOrder();
                    Product product = item.getProduct();
                    return new OrderLine(
                            order != null ? order.getId() : null,
                            order != null ? order.getOrderNumber() : null,
                            order != null ? order.getOrderDate() : null,
                            order != null && order.getStatus() != null ? order.getStatus().name() : null,
                            product != null ? product.getId() : null,
                            product != null ? product.getName() : null,
                            product != null ? product.getSku() : null,
                            nz(item.getQuantity()),
                            nz(item.getLineTotal())
                    );
                })
                .sorted(byDateDesc())
                .toList();

        return new ClientDetailsDto(lines);
    }

    private static Comparator<OrderLine> byDateDesc() {
        return Comparator.comparing(OrderLine::orderDate, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private static int nz(Integer value) {
        return value != null ? value : 0;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
