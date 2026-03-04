package com.mdm.backend.repository;

import com.mdm.backend.model.EnrolledDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EnrolledDeviceRepository extends JpaRepository<EnrolledDevice, Long> {
}