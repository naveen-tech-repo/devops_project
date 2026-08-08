package com.ecommerce.order.client;

import com.ecommerce.order.dto.UserDto;
import com.ecommerce.order.exception.DownstreamServiceException;
import com.ecommerce.order.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Component
public class UserClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public UserClient(RestTemplate restTemplate,
                      @Value("${services.user.url}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    public UserDto getUser(String userId) {
        try {
            return restTemplate.getForObject(baseUrl + "/api/users/{id}", UserDto.class, userId);
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ResourceNotFoundException("User not found: " + userId);
        } catch (RestClientException ex) {
            throw new DownstreamServiceException("user-service unavailable: " + ex.getMessage());
        }
    }
}
