package com.mdm.backend.repository;

import com.mdm.backend.model.DeviceLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceLocationRepository extends JpaRepository<DeviceLocation, Long> {

    // Get the latest location for each device under an admin
    @Query("SELECT dl FROM DeviceLocation dl WHERE dl.adminId = :adminId AND dl.timestamp = " +
            "(SELECT MAX(dl2.timestamp) FROM DeviceLocation dl2 WHERE dl2.deviceId = dl.deviceId)")
    List<DeviceLocation> findLatestLocationPerDevice(Long adminId);

    // Get latest location for a specific device
    Optional<DeviceLocation> findTopByDeviceIdOrderByTimestampDesc(String deviceId);

    // Delete all locations for a device (used when wiping device)
    @Transactional
    void deleteByDeviceId(String deviceId);
}