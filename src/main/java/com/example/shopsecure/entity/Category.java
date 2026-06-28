package com.example.shopsecure.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "categories", indexes = {
        @Index(name = "idx_categories_business_name", columnList = "business_id, name")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String description;

    @ManyToOne
    @JoinColumn(name = "business_id")
    private Business business;
}