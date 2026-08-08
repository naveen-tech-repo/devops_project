package com.ecommerce.order.service;

import com.ecommerce.order.client.ProductClient;
import com.ecommerce.order.client.UserClient;
import com.ecommerce.order.dto.CreateOrderRequest;
import com.ecommerce.order.dto.ProductDto;
import com.ecommerce.order.dto.UserDto;
import com.ecommerce.order.exception.InsufficientStockException;
import com.ecommerce.order.exception.ResourceNotFoundException;
import com.ecommerce.order.model.Order;
import com.ecommerce.order.repository.OrderRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;

@Service
public class OrderService {

    private final OrderRepository repository;
    private final ProductClient productClient;
    private final UserClient userClient;
    private final Clock clock;

    public OrderService(OrderRepository repository,
                        ProductClient productClient,
                        UserClient userClient,
                        Clock clock) {
        this.repository = repository;
        this.productClient = productClient;
        this.userClient = userClient;
        this.clock = clock;
    }

    public List<Order> findAll() {
        return repository.findAll();
    }

    public List<Order> findByUser(String userId) {
        return repository.findByUserId(userId);
    }

    public Order findById(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + id));
    }

    /**
     * Validates the user and product by calling the other two services, then stores the order.
     * Both lookups go through Kubernetes Services (or Compose DNS names) — this is the
     * service-to-service hop the cluster wiring is meant to exercise.
     */
    public Order create(CreateOrderRequest request) {
        UserDto user = userClient.getUser(request.getUserId());
        ProductDto product = productClient.getProduct(request.getProductId());

        if (product.getQuantity() < request.getQuantity()) {
            throw new InsufficientStockException(
                    "Only " + product.getQuantity() + " unit(s) of " + product.getName() + " in stock");
        }

        Order order = new Order();
        order.setUserId(user.getId());
        order.setUserEmail(user.getEmail());
        order.setProductId(product.getId());
        order.setProductName(product.getName());
        order.setQuantity(request.getQuantity());
        order.setUnitPrice(product.getPrice());
        order.setTotalPrice(product.getPrice().multiply(BigDecimal.valueOf(request.getQuantity())));
        order.setStatus("CREATED");
        order.setCreatedAt(clock.instant());

        return repository.save(order);
    }
}
