package com.example.shopsecure.repository.admin;

import com.example.shopsecure.entity.User;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

import java.util.Optional;

public interface AdminUserReadRepository extends Repository<User, Long>, JpaSpecificationExecutor<User> {
    Optional<User> findById(Long id);
}
