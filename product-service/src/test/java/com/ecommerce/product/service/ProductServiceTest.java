package com.ecommerce.product.service;

import com.ecommerce.product.exception.ResourceNotFoundException;
import com.ecommerce.product.model.Product;
import com.ecommerce.product.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductServiceTest {

    private ProductRepository repository;
    private ProductService service;

    @BeforeEach
    void setUp() {
        repository = mock(ProductRepository.class);
        service = new ProductService(repository);
    }

    @Test
    void findAllReturnsEverythingFromRepository() {
        Product p = new Product("1", "Keyboard", "Mechanical", new BigDecimal("49.99"), 10);
        when(repository.findAll()).thenReturn(List.of(p));

        assertThat(service.findAll()).containsExactly(p);
    }

    @Test
    void findByIdThrowsWhenMissing() {
        when(repository.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById("nope"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void createIgnoresClientSuppliedId() {
        when(repository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create(new Product("client-id", "Mouse", "Wireless", new BigDecimal("19.99"), 5));

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getId()).isNull();
    }

    @Test
    void updateOverwritesMutableFields() {
        Product existing = new Product("1", "Old", "Old desc", new BigDecimal("1.00"), 1);
        when(repository.findById("1")).thenReturn(Optional.of(existing));
        when(repository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        Product result = service.update("1", new Product(null, "New", "New desc", new BigDecimal("2.00"), 7));

        assertThat(result.getId()).isEqualTo("1");
        assertThat(result.getName()).isEqualTo("New");
        assertThat(result.getQuantity()).isEqualTo(7);
    }

    @Test
    void deleteRemovesExistingProduct() {
        Product existing = new Product("1", "Keyboard", null, new BigDecimal("49.99"), 10);
        when(repository.findById("1")).thenReturn(Optional.of(existing));

        service.delete("1");

        verify(repository).delete(existing);
    }
}
