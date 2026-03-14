package com.mdm.backend.repository;

import com.mdm.backend.model.DeviceInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

@Repository
public interface DeviceInfoRepository extends JpaRepository<DeviceInfo, Long> {

    Optional<DeviceInfo> findByDeviceId(String deviceId);

    @Transactional
    void deleteByDeviceId(String deviceId);
}