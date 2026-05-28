package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.CategoryRequest;
import com.example.shopsecure.dto.response.CategoryResponse;
import com.example.shopsecure.entity.Business;
import com.example.shopsecure.entity.Category;
import com.example.shopsecure.entity.User;
import com.example.shopsecure.repository.BusinessRepository;
import com.example.shopsecure.repository.CategoryRepository;
import com.example.shopsecure.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;

    public CategoryResponse createCategory(CategoryRequest request) {

        User currentUser = getCurrentUser();

        Business business = businessRepository.findByOwner_Id(currentUser.getId())
                .orElseThrow(() -> new RuntimeException("You need to create a business first"));

        if (categoryRepository.existsByNameAndBusiness_Id(request.getName(), business.getId())) {
            throw new RuntimeException("Category already exists in your store");
        }

        Category category = Category.builder()
                .name(request.getName())
                .description(request.getDescription())
                .business(business)
                .build();

        Category saved = categoryRepository.save(category);
        return toResponse(saved);
    }

    public List<CategoryResponse> getMyCategories() {

        User currentUser = getCurrentUser();

        Business business = businessRepository.findByOwner_Id(currentUser.getId())
                .orElseThrow(() -> new RuntimeException("You need to create a business first"));

        return categoryRepository.findByBusiness_Id(business.getId())
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext()
                .getAuthentication()
                .getName();

        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    private CategoryResponse toResponse(Category category) {
        return CategoryResponse.builder()
                .id(category.getId())
                .name(category.getName())
                .description(category.getDescription())
                .build();
    }
}