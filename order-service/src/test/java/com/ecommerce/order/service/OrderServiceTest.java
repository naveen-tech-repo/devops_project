package com.ecommerce.order.service;

import com.ecommerce.order.client.ProductClient;
import com.ecommerce.order.client.UserClient;
import com.ecommerce.order.dto.CreateOrderRequest;
import com.ecommerce.order.dto.ProductDto;
import com.ecommerce.order.dto.UserDto;
import com.ecommerce.order.exception.InsufficientStockException;
import com.ecommerce.order.model.Order;
import com.ecommerce.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private OrderRepository repository;
    private ProductClient productClient;
    private UserClient userClient;
    private OrderService service;

    @BeforeEach
    void setUp() {
        repository = mock(OrderRepository.class);
        productClient = mock(ProductClient.class);
        userClient = mock(UserClient.class);
        service = new OrderService(repository, productClient, userClient,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private UserDto user() {
        UserDto user = new UserDto();
        user.setId("u1");
        user.setEmail("ada@example.com");
        return user;
    }

    private ProductDto product(int stock) {
        ProductDto product = new ProductDto();
        product.setId("p1");
        product.setName("Keyboard");
        product.setPrice(new BigDecimal("25.00"));
        product.setQuantity(stock);
        return product;
    }

    private CreateOrderRequest request(int quantity) {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId("u1");
        request.setProductId("p1");
        request.setQuantity(quantity);
        return request;
    }

    @Test
    void createComputesTotalAndStampsOrder() {
        when(userClient.getUser("u1")).thenReturn(user());
        when(productClient.getProduct("p1")).thenReturn(product(10));
        when(repository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        Order order = service.create(request(3));

        assertThat(order.getTotalPrice()).isEqualByComparingTo("75.00");
        assertThat(order.getProductName()).isEqualTo("Keyboard");
        assertThat(order.getUserEmail()).isEqualTo("ada@example.com");
        assertThat(order.getStatus()).isEqualTo("CREATED");
        assertThat(order.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void createRejectsOrderLargerThanStock() {
        when(userClient.getUser("u1")).thenReturn(user());
        when(productClient.getProduct("p1")).thenReturn(product(2));

        assertThatThrownBy(() -> service.create(request(5)))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("Only 2");

        verify(repository, never()).save(any(Order.class));
    }
}
