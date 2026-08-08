package com.ecommerce.user.service;

import com.ecommerce.user.exception.ResourceNotFoundException;
import com.ecommerce.user.model.User;
import com.ecommerce.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTest {

    private UserRepository repository;
    private UserService service;

    @BeforeEach
    void setUp() {
        repository = mock(UserRepository.class);
        service = new UserService(repository);
    }

    @Test
    void findAllDelegatesToRepository() {
        User user = new User("1", "Ada", "ada@example.com", "555-0100");
        when(repository.findAll()).thenReturn(List.of(user));

        assertThat(service.findAll()).containsExactly(user);
    }

    @Test
    void findByIdThrowsWhenMissing() {
        when(repository.findById("42")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById("42"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createStripsIncomingId() {
        when(repository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create(new User("spoofed", "Grace", "grace@example.com", null));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getId()).isNull();
    }

    @Test
    void updateKeepsIdAndReplacesFields() {
        User existing = new User("1", "Old", "old@example.com", "000");
        when(repository.findById("1")).thenReturn(Optional.of(existing));
        when(repository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User updated = service.update("1", new User(null, "New", "new@example.com", "111"));

        assertThat(updated.getId()).isEqualTo("1");
        assertThat(updated.getEmail()).isEqualTo("new@example.com");
    }
}
