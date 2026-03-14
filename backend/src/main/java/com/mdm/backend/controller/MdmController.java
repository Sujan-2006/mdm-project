package com.mdm.backend.controller;

import com.mdm.backend.model.*;
import com.mdm.backend.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@CrossOrigin(origins = "*")
public class MdmController {

    @Autowired
    private EnrolledDeviceRepository enrolledDeviceRepository;

    @Autowired
    private DeviceInfoRepository deviceInfoRepository;

    @Autowired
    private AppInventoryRepository appInventoryRepository;

    @Autowired
    private AppActivityLogRepository activityLogRepository;

    @Autowired
    private EnrollmentTokenRepository enrollmentTokenRepository;

    // ── Enroll with token validation ──
    @PostMapping("/enroll")
    public ResponseEntity<String> enrollDevice(@RequestBody EnrolledDevice device) {
        try {
            if (enrolledDeviceRepository.existsByDeviceId(device.getDeviceId())) {
                return ResponseEntity.ok("Device already enrolled");
            }
            Optional<EnrollmentToken> tokenOpt =
                    enrollmentTokenRepository.findByToken(device.getEnrollmentToken());
            if (tokenOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid enrollment token!");
            }
            EnrollmentToken token = tokenOpt.get();
            if (!token.isActive()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Enrollment token is disabled!");
            }
            if (token.getExpiresAt() != null && token.getExpiresAt().isBefore(LocalDateTime.now())) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Enrollment token has expired!");
            }
            if (token.getCurrentUses() >= token.getMaxUses()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Enrollment token has reached maximum uses!");
            }
            device.setAdminId(token.getAdminId());
            device.setEnrolledAt(LocalDateTime.now());
            enrolledDeviceRepository.save(device);
            token.setCurrentUses(token.getCurrentUses() + 1);
            enrollmentTokenRepository.save(token);
            return ResponseEntity.status(HttpStatus.CREATED).body("Device enrolled successfully");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Enrollment failed: " + e.getMessage());
        }
    }

    @PostMapping("/device-info")
    public ResponseEntity<String> saveDeviceInfo(@RequestBody DeviceInfo deviceInfo) {
        deviceInfoRepository.save(deviceInfo);
        return ResponseEntity.ok("Device info saved");
    }

    @PostMapping("/app-inventory")
    public ResponseEntity<String> saveAppInventory(@RequestBody List<AppInventory> apps) {
        if (apps.isEmpty()) return ResponseEntity.ok("No apps");
        String deviceId = apps.get(0).getDeviceId();
        appInventoryRepository.deleteByDeviceId(deviceId);
        appInventoryRepository.saveAll(apps);
        return ResponseEntity.ok("App inventory saved");
    }

    // ── GET endpoints for dashboard ──
    @GetMapping("/api/devices")
    public ResponseEntity<List<EnrolledDevice>> getAllDevices(@RequestParam Long adminId) {
        return ResponseEntity.ok(enrolledDeviceRepository.findByAdminId(adminId));
    }

    @GetMapping("/api/devices/{deviceId}/info")
    public ResponseEntity<?> getDeviceInfo(@PathVariable String deviceId) {
        try {
            return deviceInfoRepository.findByDeviceId(deviceId)
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.ok().build());
        } catch (Exception e) {
            return ResponseEntity.ok(new HashMap<>());
        }
    }

    @GetMapping("/api/devices/{deviceId}/apps")
    public ResponseEntity<List<AppInventory>> getDeviceApps(@PathVariable String deviceId) {
        return ResponseEntity.ok(appInventoryRepository.findByDeviceId(deviceId));
    }

    // ── DELETE device — wipes ALL data for that device ──
    @DeleteMapping("/api/devices/{deviceId}")
    public ResponseEntity<?> deleteDevice(@PathVariable String deviceId) {
        try {
            // Delete all related data in correct order
            activityLogRepository.deleteByDeviceId(deviceId);
            appInventoryRepository.deleteByDeviceId(deviceId);
            deviceInfoRepository.deleteByDeviceId(deviceId);
            enrolledDeviceRepository.deleteByDeviceId(deviceId);
            return ResponseEntity.ok("Device and all associated data deleted successfully");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to delete device: " + e.getMessage());
        }
    }

    @GetMapping("/api/stats")
    public ResponseEntity<?> getStats(@RequestParam Long adminId) {
        long totalDevices = enrolledDeviceRepository.countByAdminId(adminId);
        List<String> deviceIds = enrolledDeviceRepository.findByAdminId(adminId)
                .stream().map(EnrolledDevice::getDeviceId).toList();
        long totalApps  = appInventoryRepository.countByDeviceIdIn(deviceIds);
        long systemApps = appInventoryRepository.countByDeviceIdInAndIsSystemApp(deviceIds, true);
        long userApps   = appInventoryRepository.countByDeviceIdInAndIsSystemApp(deviceIds, false);
        Map<String, Long> stats = new HashMap<>();
        stats.put("totalDevices", totalDevices);
        stats.put("totalApps",    totalApps);
        stats.put("systemApps",   systemApps);
        stats.put("userApps",     userApps);
        return ResponseEntity.ok(stats);
    }

    // ── Token management ──
    @GetMapping("/api/tokens")
    public ResponseEntity<List<EnrollmentToken>> getAllTokens(@RequestParam Long adminId) {
        return ResponseEntity.ok(enrollmentTokenRepository.findByAdminId(adminId));
    }

    @PostMapping("/api/tokens")
    public ResponseEntity<?> createToken(@RequestBody EnrollmentToken token) {
        try {
            if (token.getToken() == null || token.getToken().isEmpty()) {
                token.setToken(UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            }
            token.setCreatedAt(LocalDateTime.now());
            token.setCurrentUses(0);
            token.setActive(true);
            if (token.getMaxUses() <= 0) token.setMaxUses(1);
            return ResponseEntity.ok(enrollmentTokenRepository.save(token));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Failed: " + e.getMessage());
        }
    }

    @PutMapping("/api/tokens/{id}/toggle")
    public ResponseEntity<?> toggleToken(@PathVariable Long id) {
        return enrollmentTokenRepository.findById(id).map(token -> {
            token.setActive(!token.isActive());
            enrollmentTokenRepository.save(token);
            return ResponseEntity.ok(token);
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/api/tokens/{id}")
    public ResponseEntity<?> deleteToken(@PathVariable Long id) {
        enrollmentTokenRepository.deleteById(id);
        return ResponseEntity.ok("Token deleted");
    }

    // ── Activity Log ──
    @PostMapping("/api/activity-log")
    public ResponseEntity<?> logActivity(@RequestBody AppActivityLog log) {
        log.setTimestamp(LocalDateTime.now());
        activityLogRepository.save(log);
        return ResponseEntity.ok("logged");
    }

    @GetMapping("/api/activity-log")
    public List<AppActivityLog> getActivityLog(@RequestParam Long adminId) {
        return activityLogRepository.findByAdminIdOrderByTimestampDesc(
                adminId, PageRequest.of(0, 20));
    }

    @GetMapping("/api/device-admin")
    public ResponseEntity<?> getAdminIdForDevice(@RequestParam String deviceId) {
        return enrolledDeviceRepository.findByDeviceId(deviceId)
                .map(device -> {
                    Map<String, Long> res = new HashMap<>();
                    res.put("adminId", device.getAdminId());
                    return ResponseEntity.ok(res);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}