package com.example.shopsecure.service;

import com.example.shopsecure.dto.response.BusinessResponse;
import com.example.shopsecure.dto.response.CategoryResponse;
import com.example.shopsecure.dto.response.ProductResponse;
import com.example.shopsecure.entity.Business;
import com.example.shopsecure.entity.Category;
import com.example.shopsecure.entity.Product;
import com.example.shopsecure.repository.BusinessRepository;
import com.example.shopsecure.repository.CategoryRepository;
import com.example.shopsecure.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StorefrontService {

    private final BusinessRepository businessRepository;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    @Value("${app.base-url}")
    private String baseUrl;

    @Cacheable(value = "storefront", key = "#slug")
    public BusinessResponse getStorefront(String slug) {
        Business business = businessRepository.findByStorefrontSlug(slug)
                .orElseThrow(() -> new RuntimeException("Store not found"));
        return toBusinessResponse(business);
    }

    @Cacheable(value = "storeProducts", key = "#slug")
    public List<ProductResponse> getStoreProducts(String slug) {
        return productRepository.findBySlugWithDetails(slug)
                .stream()
                .map(this::toProductResponse)
                .collect(Collectors.toList());
    }

    @Cacheable(value = "storeProduct", key = "#slug + '-' + #productId")
    public ProductResponse getStoreProduct(String slug, Long productId) {
        return productRepository.findByIdAndSlugWithDetails(productId, slug)
                .map(this::toProductResponse)
                .orElseThrow(() -> new RuntimeException("Product not found in this store"));
    }

    @Cacheable(value = "storeCategories", key = "#slug")
    public List<CategoryResponse> getStoreCategories(String slug) {
        Business business = businessRepository.findByStorefrontSlug(slug)
                .orElseThrow(() -> new RuntimeException("Store not found"));

        return categoryRepository.findByBusiness_Id(business.getId())
                .stream()
                .map(this::toCategoryResponse)
                .collect(Collectors.toList());
    }

    @Cacheable(value = "storeProductsByCategory", key = "#slug + '-' + #categoryId")
    public List<ProductResponse> getStoreProductsByCategory(String slug, Long categoryId) {
        return productRepository.findBySlugAndCategoryWithDetails(slug, categoryId)
                .stream()
                .map(this::toProductResponse)
                .collect(Collectors.toList());
    }

    @CacheEvict(value = {"storeProducts", "storeProductsByCategory", "storeProduct"}, allEntries = true)
    public void evictProductCaches() {
        // Call this from your ProductService whenever a product is created, updated, or deleted
    }

    private BusinessResponse toBusinessResponse(Business business) {
        return BusinessResponse.builder()
                .id(business.getId())
                .businessName(business.getBusinessName())
                .storefrontSlug(business.getStorefrontSlug())
                .storefrontUrl(baseUrl + "/" + business.getStorefrontSlug())
                .description(business.getDescription())
                .phoneNumber(business.getPhoneNumber())
                .address(business.getAddress())
                .createdAt(business.getCreatedAt())
                .build();
    }

    private ProductResponse toProductResponse(Product product) {
        return ProductResponse.builder()
                .id(product.getId())
                .name(product.getName())
                .description(product.getDescription())
                .price(product.getPrice())
                .stock(product.getStock())
                .imageUrls(product.getImageUrls())
                .categoryName(product.getCategory().getName())
                .businessName(product.getBusiness().getBusinessName())
                .createdAt(product.getCreatedAt())
                .build();
    }

    private CategoryResponse toCategoryResponse(Category category) {
        return CategoryResponse.builder()
                .id(category.getId())
                .name(category.getName())
                .description(category.getDescription())
                .build();
    }
}