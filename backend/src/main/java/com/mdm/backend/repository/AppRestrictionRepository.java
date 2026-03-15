package com.mdm.backend.repository;

import com.mdm.backend.model.AppRestriction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface AppRestrictionRepository extends JpaRepository<AppRestriction, Long> {
    List<AppRestriction> findByAdminId(Long adminId);
    Optional<AppRestriction> findByAdminIdAndPackageName(Long adminId, String packageName);
    boolean existsByAdminIdAndPackageName(Long adminId, String packageName);
    @org.springframework.transaction.annotation.Transactional
    void deleteByAdminIdAndPackageName(Long adminId, String packageName);
}