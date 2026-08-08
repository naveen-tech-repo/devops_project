package com.ecommerce.order.client;

import com.ecommerce.order.dto.ProductDto;
import com.ecommerce.order.exception.DownstreamServiceException;
import com.ecommerce.order.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Component
public class ProductClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public ProductClient(RestTemplate restTemplate,
                         @Value("${services.product.url}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    public ProductDto getProduct(String productId) {
        try {
            return restTemplate.getForObject(baseUrl + "/api/products/{id}", ProductDto.class, productId);
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ResourceNotFoundException("Product not found: " + productId);
        } catch (RestClientException ex) {
            throw new DownstreamServiceException("product-service unavailable: " + ex.getMessage());
        }
    }
}
