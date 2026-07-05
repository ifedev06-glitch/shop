package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.CreateProductRequest;
import com.example.shopsecure.dto.response.ProductResponse;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final CloudinaryService cloudinaryService;

    public ProductResponse createProduct(CreateProductRequest request, List<MultipartFile> images) {

        User currentUser = getCurrentUser();

        Business business = businessRepository.findByOwner_Id(currentUser.getId())
                .orElseThrow(() -> new BusinessException("You need to create a business first")); // ← changed

        Category category = categoryRepository.findByIdAndBusiness_Id(
                        request.getCategoryId(), business.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found in your store")); // ← changed

        List<String> imageUrls = new ArrayList<>();
        if (images != null && !images.isEmpty()) {
            for (MultipartFile image : images) {
                String url = cloudinaryService.upload(image);
                imageUrls.add(url);
            }
        }

        Product product = Product.builder()
                .name(request.getName())
                .description(request.getDescription())
                .price(request.getPrice())
                .costPrice(request.getCostPrice())
                .stock(request.getStock())
                .lowStockThreshold(request.getLowStockThreshold() != null
                        ? request.getLowStockThreshold() : 5)
                .imageUrls(imageUrls)
                .category(category)
                .business(business)
                .createdAt(LocalDateTime.now())
                .build();

        Product saved = productRepository.save(product);
        log.info("Product created: productId={}, businessId={}, name={}", saved.getId(), business.getId(), saved.getName());
        return toResponse(saved);
    }

    public List<ProductResponse> getMyProducts() {

        User currentUser = getCurrentUser();

        Business business = businessRepository.findByOwner_Id(currentUser.getId())
                .orElseThrow(() -> new BusinessException("You need to create a business first")); // ← changed

        List<ProductResponse> products = productRepository.findByBusiness_Id(business.getId())
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        log.info("Fetched {} products for businessId={}", products.size(), business.getId());
        return products;
    }

    public Page<ProductResponse> searchProducts(String query, int page, int size) {
        User currentUser = getCurrentUser();
        Business business = businessRepository.findByOwner_Id(currentUser.getId())
                .orElseThrow(() -> new BusinessException("You need to create a business first"));

        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Product> productPage;
        if (query != null && !query.isBlank()) {
            productPage = productRepository.findByBusiness_IdAndNameContainingIgnoreCase(
                    business.getId(), query, pageable);
        } else {
            productPage = productRepository.findByBusiness_Id(business.getId(), pageable);
        }
        return productPage.map(this::toResponse);
    }

    public ProductResponse getProductById(Long productId) {

        User currentUser = getCurrentUser();

        Business business = businessRepository.findByOwner_Id(currentUser.getId())
                .orElseThrow(() -> new BusinessException("You need to create a business first")); // ← changed

        Product product = productRepository.findByIdAndBusiness_Id(productId, business.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found in your store")); // ← changed

        return toResponse(product);
    }

    public ProductResponse updateProduct(Long productId, CreateProductRequest request, List<MultipartFile> images) {

        User currentUser = getCurrentUser();

        Business business = businessRepository.findByOwner_Id(currentUser.getId())
                .orElseThrow(() -> new BusinessException("You need to create a business first")); // ← changed

        Product product = productRepository.findByIdAndBusiness_Id(productId, business.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found in your store")); // ← changed

        Category category = categoryRepository.findByIdAndBusiness_Id(
                        request.getCategoryId(), business.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found in your store")); // ← changed

        if (images != null && !images.isEmpty()) {
            if (product.getImageUrls() != null) {
                product.getImageUrls().forEach(cloudinaryService::delete);
            }
            List<String> newImageUrls = new ArrayList<>();
            for (MultipartFile image : images) {
                String url = cloudinaryService.upload(image);
                newImageUrls.add(url);
            }
            product.setImageUrls(newImageUrls);
        }

        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        product.setCostPrice(request.getCostPrice());
        product.setStock(request.getStock());
        product.setLowStockThreshold(request.getLowStockThreshold() != null
                ? request.getLowStockThreshold() : 5);
        product.setCategory(category);

        Product saved = productRepository.save(product);
        log.info("Product updated: productId={}, businessId={}", saved.getId(), business.getId());
        return toResponse(saved);
    }

    public ProductResponse patchProduct(Long productId, CreateProductRequest request) {
        User currentUser = getCurrentUser();
        Business business = businessRepository.findByOwner_Id(currentUser.getId())
                .orElseThrow(() -> new BusinessException("You need to create a business first"));

        Product product = productRepository.findByIdAndBusiness_Id(productId, business.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found in your store"));

        if (request.getName() != null && !request.getName().isBlank()) {
            product.setName(request.getName());
        }
        if (request.getDescription() != null && !request.getDescription().isBlank()) {
            product.setDescription(request.getDescription());
        }
        if (request.getPrice() != null) {
            product.setPrice(request.getPrice());
        }
        if (request.getCostPrice() != null) {
            product.setCostPrice(request.getCostPrice());
        }
        if (request.getStock() != null) {
            product.setStock(request.getStock());
        }
        if (request.getLowStockThreshold() != null) {
            product.setLowStockThreshold(request.getLowStockThreshold());
        }
        if (request.getCategoryId() != null) {
            Category category = categoryRepository.findByIdAndBusiness_Id(
                            request.getCategoryId(), business.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found in your store"));
            product.setCategory(category);
        }

        Product saved = productRepository.save(product);
        log.info("Product patched: productId={}, businessId={}", saved.getId(), business.getId());
        return toResponse(saved);
    }

    public void deleteProduct(Long productId) {

        User currentUser = getCurrentUser();

        Business business = businessRepository.findByOwner_Id(currentUser.getId())
                .orElseThrow(() -> new BusinessException("You need to create a business first")); // ← changed

        Product product = productRepository.findByIdAndBusiness_Id(productId, business.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found in your store")); // ← changed

        if (product.getImageUrls() != null) {
            product.getImageUrls().forEach(cloudinaryService::delete);
        }

        productRepository.delete(product);
        log.info("Product deleted: productId={}, businessId={}", productId, business.getId());
    }

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext()
                .getAuthentication()
                .getName();

        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found")); // ← changed
    }

    private ProductResponse toResponse(Product product) {
        return ProductResponse.builder()
                .id(product.getId())
                .name(product.getName())
                .description(product.getDescription())
                .price(product.getPrice())
                .costPrice(product.getCostPrice())
                .stock(product.getStock())
                .lowStockThreshold(product.getLowStockThreshold())
                .imageUrls(product.getImageUrls())
                .categoryName(product.getCategory().getName())
                .businessName(product.getBusiness().getBusinessName())
                .createdAt(product.getCreatedAt())
                .build();
    }
}