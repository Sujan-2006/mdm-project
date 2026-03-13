package com.mdm.backend.repository;

import com.mdm.backend.model.EnrolledDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface EnrolledDeviceRepository
        extends JpaRepository<EnrolledDevice, Long> {
    boolean existsByDeviceId(String deviceId);
    List<EnrolledDevice> findByAdminId(Long adminId);
    long countByAdminId(Long adminId);
    Optional<EnrolledDevice> findByDeviceId(String deviceId);
}