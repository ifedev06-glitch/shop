package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.CreateProductRequest;
import com.example.shopsecure.dto.response.ProductResponse;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final CloudinaryService cloudinaryService;

    public ProductResponse createProduct(CreateProductRequest request, List<MultipartFile> images) {

        User currentUser = getCurrentUser();

        Business business = businessRepository.findByOwner_Id(currentUser.getId())
                .orElseThrow(() -> new RuntimeException("You need to create a business first"));

        // Validate category belongs to this vendor's business
        Category category = categoryRepository.findByIdAndBusiness_Id(
                        request.getCategoryId(), business.getId())
                .orElseThrow(() -> new RuntimeException("Category not found in your store"));

        // Upload images to Cloudinary
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
                .stock(request.getStock())
                .imageUrls(imageUrls)
                .category(category)
                .business(business)
                .createdAt(LocalDateTime.now())
                .build();

        Product saved = productRepository.save(product);
        return toResponse(saved);
    }

    public List<ProductResponse> getMyProducts() {

        User currentUser = getCurrentUser();

        Business business = businessRepository.findByOwner_Id(currentUser.getId())
                .orElseThrow(() -> new RuntimeException("You need to create a business first"));

        return productRepository.findByBusiness_Id(business.getId())
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public ProductResponse getProductById(Long productId) {

        User currentUser = getCurrentUser();

        Business business = businessRepository.findByOwner_Id(currentUser.getId())
                .orElseThrow(() -> new RuntimeException("You need to create a business first"));

        Product product = productRepository.findByIdAndBusiness_Id(productId, business.getId())
                .orElseThrow(() -> new RuntimeException("Product not found in your store"));

        return toResponse(product);
    }

    public ProductResponse updateProduct(Long productId, CreateProductRequest request, List<MultipartFile> images) {

        User currentUser = getCurrentUser();

        Business business = businessRepository.findByOwner_Id(currentUser.getId())
                .orElseThrow(() -> new RuntimeException("You need to create a business first"));

        Product product = productRepository.findByIdAndBusiness_Id(productId, business.getId())
                .orElseThrow(() -> new RuntimeException("Product not found in your store"));

        // Validate category belongs to this vendor's business
        Category category = categoryRepository.findByIdAndBusiness_Id(
                        request.getCategoryId(), business.getId())
                .orElseThrow(() -> new RuntimeException("Category not found in your store"));

        // Upload new images if provided, otherwise keep existing
        if (images != null && !images.isEmpty()) {
            // Delete old images from Cloudinary
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
        product.setStock(request.getStock());
        product.setCategory(category);

        Product saved = productRepository.save(product);
        return toResponse(saved);
    }

    public void deleteProduct(Long productId) {

        User currentUser = getCurrentUser();

        Business business = businessRepository.findByOwner_Id(currentUser.getId())
                .orElseThrow(() -> new RuntimeException("You need to create a business first"));

        Product product = productRepository.findByIdAndBusiness_Id(productId, business.getId())
                .orElseThrow(() -> new RuntimeException("Product not found in your store"));

        // Delete images from Cloudinary
        if (product.getImageUrls() != null) {
            product.getImageUrls().forEach(cloudinaryService::delete);
        }

        productRepository.delete(product);
    }

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext()
                .getAuthentication()
                .getName();

        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    private ProductResponse toResponse(Product product) {
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
}