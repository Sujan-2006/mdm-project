package com.mdm.backend.repository;

import com.mdm.backend.model.AppActivityLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AppActivityLogRepository extends JpaRepository<AppActivityLog, Long> {
    List<AppActivityLog> findTop50ByAdminIdOrderByTimestampDesc(Long adminId);
}