package com.mdm.backend.repository;

import com.mdm.backend.model.EnrollmentToken;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface EnrollmentTokenRepository
        extends JpaRepository<EnrollmentToken, Long> {
    Optional<EnrollmentToken> findByToken(String token);
    List<EnrollmentToken> findByAdminId(Long adminId);
}