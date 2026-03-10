package com.mdm.backend.controller;

import com.mdm.backend.model.*;
import com.mdm.backend.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
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
    private EnrollmentTokenRepository enrollmentTokenRepository;

    // ── Enroll with token validation ──
    @PostMapping("/enroll")
    public ResponseEntity<String> enrollDevice(
            @RequestBody EnrolledDevice device) {
        try {
            // Check if device already enrolled
            if (enrolledDeviceRepository
                    .existsByDeviceId(device.getDeviceId())) {
                return ResponseEntity
                        .ok("Device already enrolled");
            }

            // Find token in database
            Optional<EnrollmentToken> tokenOpt =
                    enrollmentTokenRepository
                            .findByToken(device.getEnrollmentToken());

            // Token not found
            if (tokenOpt.isEmpty()) {
                return ResponseEntity
                        .status(HttpStatus.UNAUTHORIZED)
                        .body("Invalid enrollment token!");
            }

            EnrollmentToken token = tokenOpt.get();

            // Token not active
            if (!token.isActive()) {
                return ResponseEntity
                        .status(HttpStatus.UNAUTHORIZED)
                        .body("Enrollment token is disabled!");
            }

            // Token expired
            if (token.getExpiresAt() != null &&
                    token.getExpiresAt()
                            .isBefore(LocalDateTime.now())) {
                return ResponseEntity
                        .status(HttpStatus.UNAUTHORIZED)
                        .body("Enrollment token has expired!");
            }

            // Token max uses reached
            if (token.getCurrentUses() >= token.getMaxUses()) {
                return ResponseEntity
                        .status(HttpStatus.UNAUTHORIZED)
                        .body("Enrollment token has reached maximum uses!");
            }

            // Set adminId from token so device belongs to same admin
            device.setAdminId(token.getAdminId());
            device.setEnrolledAt(LocalDateTime.now());
            enrolledDeviceRepository.save(device);

            // Increment token usage
            token.setCurrentUses(token.getCurrentUses() + 1);
            enrollmentTokenRepository.save(token);

            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body("Device enrolled successfully");

        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Enrollment failed: " + e.getMessage());
        }
    }

    @PostMapping("/device-info")
    public ResponseEntity<String> saveDeviceInfo(
            @RequestBody DeviceInfo deviceInfo) {
        deviceInfoRepository.save(deviceInfo);
        return ResponseEntity.ok("Device info saved");
    }

    @PostMapping("/app-inventory")
    public ResponseEntity<String> saveAppInventory(
            @RequestBody List<AppInventory> apps) {
        if (apps.isEmpty())
            return ResponseEntity.ok("No apps");
        String deviceId = apps.get(0).getDeviceId();
        appInventoryRepository.deleteByDeviceId(deviceId);
        appInventoryRepository.saveAll(apps);
        return ResponseEntity.ok("App inventory saved");
    }

    // ── GET endpoints for dashboard (filtered by adminId) ──
    @GetMapping("/api/devices")
    public ResponseEntity<List<EnrolledDevice>> getAllDevices(
            @RequestParam Long adminId) {
        return ResponseEntity.ok(
                enrolledDeviceRepository.findByAdminId(adminId));
    }

    @GetMapping("/api/devices/{deviceId}/info")
    public ResponseEntity<?> getDeviceInfo(
            @PathVariable String deviceId) {
        try {
            return deviceInfoRepository
                    .findByDeviceId(deviceId)
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.ok().build());
        } catch (Exception e) {
            return ResponseEntity.ok(new HashMap<>());
        }
    }

    @GetMapping("/api/devices/{deviceId}/apps")
    public ResponseEntity<List<AppInventory>> getDeviceApps(
            @PathVariable String deviceId) {
        return ResponseEntity.ok(
                appInventoryRepository.findByDeviceId(deviceId));
    }

    @GetMapping("/api/stats")
    public ResponseEntity<?> getStats(
            @RequestParam Long adminId) {
        long totalDevices = enrolledDeviceRepository
                .countByAdminId(adminId);
        long totalApps = appInventoryRepository
                .countByDeviceIdIn(
                        enrolledDeviceRepository
                                .findByAdminId(adminId)
                                .stream()
                                .map(EnrolledDevice::getDeviceId)
                                .toList());
        Map<String, Long> stats = new HashMap<>();
        stats.put("totalDevices", totalDevices);
        stats.put("totalApps",    totalApps);
        return ResponseEntity.ok(stats);
    }

    // ── Token management endpoints (filtered by adminId) ──
    @GetMapping("/api/tokens")
    public ResponseEntity<List<EnrollmentToken>> getAllTokens(
            @RequestParam Long adminId) {
        return ResponseEntity.ok(
                enrollmentTokenRepository.findByAdminId(adminId));
    }

    @PostMapping("/api/tokens")
    public ResponseEntity<?> createToken(
            @RequestBody EnrollmentToken token) {
        try {
            if (token.getToken() == null ||
                    token.getToken().isEmpty()) {
                token.setToken(UUID.randomUUID()
                        .toString()
                        .substring(0, 8)
                        .toUpperCase());
            }
            token.setCreatedAt(LocalDateTime.now());
            token.setCurrentUses(0);
            token.setActive(true);
            if (token.getMaxUses() <= 0)
                token.setMaxUses(1);
            EnrollmentToken saved =
                    enrollmentTokenRepository.save(token);
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body("Failed: " + e.getMessage());
        }
    }

    @PutMapping("/api/tokens/{id}/toggle")
    public ResponseEntity<?> toggleToken(
            @PathVariable Long id) {
        return enrollmentTokenRepository
                .findById(id).map(token -> {
                    token.setActive(!token.isActive());
                    enrollmentTokenRepository.save(token);
                    return ResponseEntity.ok(token);
                }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/api/tokens/{id}")
    public ResponseEntity<?> deleteToken(
            @PathVariable Long id) {
        enrollmentTokenRepository.deleteById(id);
        return ResponseEntity.ok("Token deleted");
    }
}