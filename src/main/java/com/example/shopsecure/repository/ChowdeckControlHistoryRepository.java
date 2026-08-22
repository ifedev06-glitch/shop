package com.example.shopsecure.repository;

import com.example.shopsecure.entity.ChowdeckControlHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

public interface ChowdeckControlHistoryRepository extends Repository<ChowdeckControlHistory, Long> {
    <S extends ChowdeckControlHistory> S save(S history);

    Page<ChowdeckControlHistory> findAllByOrderByControlVersionDesc(Pageable pageable);
}
