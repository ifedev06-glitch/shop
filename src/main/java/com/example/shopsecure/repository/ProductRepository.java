package com.example.shopsecure.repository;

import com.example.shopsecure.entity.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByBusiness_Id(Long businessId);

    Optional<Product> findByIdAndBusiness_Id(Long id, Long businessId);

    List<Product> findByBusiness_IdAndCategory_Id(Long businessId, Long categoryId);

    @Query("SELECT p FROM Product p " +
            "JOIN FETCH p.category " +
            "JOIN FETCH p.business b " +
            "WHERE b.storefrontSlug = :slug")
    List<Product> findBySlugWithDetails(@Param("slug") String slug);

    @Query("SELECT p FROM Product p " +
            "JOIN FETCH p.category " +
            "JOIN FETCH p.business b " +
            "WHERE p.id = :productId AND b.storefrontSlug = :slug")
    Optional<Product> findByIdAndSlugWithDetails(@Param("productId") Long productId,
                                                 @Param("slug") String slug);

    @Query("SELECT p FROM Product p " +
            "JOIN FETCH p.category c " +
            "JOIN FETCH p.business b " +
            "WHERE b.storefrontSlug = :slug AND c.id = :categoryId")
    List<Product> findBySlugAndCategoryWithDetails(@Param("slug") String slug,
                                                   @Param("categoryId") Long categoryId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Long id);
}