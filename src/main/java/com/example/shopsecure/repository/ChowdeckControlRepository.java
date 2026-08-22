package com.example.shopsecure.repository;

import com.example.shopsecure.entity.ChowdeckControl;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.Optional;

public interface ChowdeckControlRepository extends Repository<ChowdeckControl, Long> {

    Optional<ChowdeckControl> findById(Long id);

    <S extends ChowdeckControl> S save(S control);

    void flush();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ChowdeckControl c where c.id = 1")
    Optional<ChowdeckControl> findSingletonForUpdate();
}
