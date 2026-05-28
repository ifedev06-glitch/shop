package com.example.shopsecure.entity;


import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "storefronts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Storefront {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String storeName;

//    private String logoUrl;
//
//    private String bannerUrl;

    private String description;

    @Column(unique = true)
    private String slug;

    @OneToOne
    @JoinColumn(name = "business_id")
    private Business business;
}
