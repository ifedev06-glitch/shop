package com.example.shopsecure.repository.admin;

import com.example.shopsecure.entity.Wallet;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AdminWalletReadRepository extends Repository<Wallet, Long> {
    Optional<Wallet> findByUser_Id(Long userId);

    List<Wallet> findByUser_IdIn(Collection<Long> userIds);
}
