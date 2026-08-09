package com.example.shopsecure.repository;

import com.example.shopsecure.entity.OtpChallenge;
import com.example.shopsecure.entity.OtpPurpose;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.List;

public interface OtpChallengeRepository extends JpaRepository<OtpChallenge, Long> {

    Optional<OtpChallenge> findTopByPurposeAndSubjectOrderByCreatedAtDesc(
            OtpPurpose purpose,
            String subject
    );

    Optional<OtpChallenge> findByPublicId(String publicId);

    List<OtpChallenge> findByPurposeAndSubjectAndConsumedAtIsNullAndInvalidatedAtIsNull(
            OtpPurpose purpose,
            String subject
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OtpChallenge> findByPublicIdAndPurposeAndSubject(
            String publicId,
            OtpPurpose purpose,
            String subject
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OtpChallenge> findFirstByPurposeAndSubjectOrderByCreatedAtDesc(
            OtpPurpose purpose,
            String subject
    );
}
