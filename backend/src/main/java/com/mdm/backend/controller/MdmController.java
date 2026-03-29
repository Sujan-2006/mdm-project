package com.mdm.backend.controller;

import com.mdm.backend.AMAPIService;
import com.mdm.backend.FCMService;
import com.mdm.backend.model.*;
import com.mdm.backend.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
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

    @Autowired private EnrolledDeviceRepository enrolledDeviceRepository;
    @Autowired private DeviceInfoRepository deviceInfoRepository;
    @Autowired private AppInventoryRepository appInventoryRepository;
    @Autowired private AppActivityLogRepository activityLogRepository;
    @Autowired private EnrollmentTokenRepository enrollmentTokenRepository;
    @Autowired private DeviceLocationRepository deviceLocationRepository;
    @Autowired private AppRestrictionRepository appRestrictionRepository;
    @Autowired private FCMService fcmService;
    @Autowired private AMAPIService amapiService;

    // ── Enroll ──
    @PostMapping("/enroll")
    public ResponseEntity<String> enrollDevice(@RequestBody EnrolledDevice device) {
        try {
            if (enrolledDeviceRepository.existsByDeviceId(device.getDeviceId()))
                return ResponseEntity.ok("Device already enrolled");

            Optional<EnrollmentToken> tokenOpt =
                    enrollmentTokenRepository.findByToken(device.getEnrollmentToken());
            if (tokenOpt.isEmpty())
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid enrollment token!");

            EnrollmentToken token = tokenOpt.get();
            if (!token.isActive())
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Enrollment token is disabled!");
            if (token.getExpiresAt() != null && token.getExpiresAt().isBefore(LocalDateTime.now()))
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Enrollment token has expired!");
            if (token.getCurrentUses() >= token.getMaxUses())
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Enrollment token has reached maximum uses!");

            device.setAdminId(token.getAdminId());
            device.setEnrolledAt(LocalDateTime.now());
            device.setLastSeen(LocalDateTime.now());
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
    @Transactional(noRollbackFor = DataAccessException.class)
    public ResponseEntity<String> saveAppInventory(@RequestBody List<AppInventory> apps) {
        if (apps.isEmpty()) return ResponseEntity.ok("No apps");
        String deviceId = apps.get(0).getDeviceId();
        try {
            appInventoryRepository.deleteByDeviceId(deviceId);
            appInventoryRepository.flush();
            appInventoryRepository.saveAll(apps);
            enrolledDeviceRepository.findByDeviceId(deviceId).ifPresent(device -> {
                device.setLastSeen(LocalDateTime.now());
                enrolledDeviceRepository.save(device);
            });
            return ResponseEntity.ok("App inventory saved");
        } catch (DataAccessException e) {
            return ResponseEntity.ok("App inventory sync skipped (concurrent request)");
        }
    }

    // ── Ping ──
    @PostMapping("/api/device-ping")
    public ResponseEntity<?> devicePing(@RequestParam String deviceId) {
        return enrolledDeviceRepository.findByDeviceId(deviceId).map(device -> {
            device.setLastSeen(LocalDateTime.now());
            enrolledDeviceRepository.save(device);
            return ResponseEntity.ok("pong");
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── FCM Token ──
    @PostMapping("/api/device/fcm-token")
    public ResponseEntity<?> updateFcmToken(@RequestParam String deviceId,
                                            @RequestParam String token) {
        return enrolledDeviceRepository.findByDeviceId(deviceId).map(device -> {
            device.setFcmToken(token);
            enrolledDeviceRepository.save(device);
            return ResponseEntity.ok("FCM token updated");
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── Location ──
    @PostMapping("/api/device-location")
    public ResponseEntity<?> saveLocation(@RequestBody DeviceLocation location) {
        location.setTimestamp(LocalDateTime.now());
        deviceLocationRepository.save(location);
        enrolledDeviceRepository.findByDeviceId(location.getDeviceId()).ifPresent(device -> {
            device.setLastSeen(LocalDateTime.now());
            enrolledDeviceRepository.save(device);
        });
        return ResponseEntity.ok("Location saved");
    }

    @GetMapping("/api/device-locations")
    public ResponseEntity<List<DeviceLocation>> getLocations(@RequestParam Long adminId) {
        return ResponseEntity.ok(deviceLocationRepository.findLatestLocationPerDevice(adminId));
    }

    @GetMapping("/api/device-location/{deviceId}")
    public ResponseEntity<?> getDeviceLocation(@PathVariable String deviceId) {
        return deviceLocationRepository.findTopByDeviceIdOrderByTimestampDesc(deviceId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Devices ──
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

    @DeleteMapping("/api/devices/{deviceId}")
    public ResponseEntity<?> deleteDevice(@PathVariable String deviceId) {
        try {
            activityLogRepository.deleteByDeviceId(deviceId);
            appInventoryRepository.deleteByDeviceId(deviceId);
            deviceInfoRepository.deleteByDeviceId(deviceId);
            deviceLocationRepository.deleteByDeviceId(deviceId);
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

    // ── Tokens ──
    @GetMapping("/api/tokens")
    public ResponseEntity<List<EnrollmentToken>> getAllTokens(@RequestParam Long adminId) {
        return ResponseEntity.ok(enrollmentTokenRepository.findByAdminId(adminId));
    }

    @PostMapping("/api/tokens")
    public ResponseEntity<?> createToken(@RequestBody EnrollmentToken token) {
        try {
            if (token.getToken() == null || token.getToken().isEmpty())
                token.setToken(UUID.randomUUID().toString().substring(0, 8).toUpperCase());
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

    // ── App Restrictions ─────────────────────────────────────────────────

    @GetMapping("/api/restrictions")
    public ResponseEntity<List<AppRestriction>> getRestrictions(@RequestParam Long adminId) {
        return ResponseEntity.ok(appRestrictionRepository.findByAdminId(adminId));
    }

    @GetMapping("/api/restrictions/packages")
    public ResponseEntity<List<String>> getRestrictedPackages(@RequestParam String deviceId) {
        return enrolledDeviceRepository.findByDeviceId(deviceId)
                .map(device -> {
                    List<String> packages = appRestrictionRepository
                            .findByAdminIdAndForceInstall(device.getAdminId(), false)
                            .stream()
                            .map(AppRestriction::getPackageName)
                            .toList();
                    return ResponseEntity.ok(packages);
                })
                .orElse(ResponseEntity.ok(List.of()));
    }

    @PostMapping("/api/restrictions")
    public ResponseEntity<?> blockApp(@RequestBody AppRestriction restriction) {
        if (appRestrictionRepository.existsByAdminIdAndPackageName(
                restriction.getAdminId(), restriction.getPackageName())) {
            return ResponseEntity.badRequest().body("App already blocked");
        }
        restriction.setCreatedAt(LocalDateTime.now());
        restriction.setForceInstall(false);
        appRestrictionRepository.save(restriction);

        List<EnrolledDevice> devices = enrolledDeviceRepository
                .findByAdminId(restriction.getAdminId());
        for (EnrolledDevice device : devices) {
            if (device.getFcmToken() != null && !device.getFcmToken().isEmpty()) {
                fcmService.sendEnforceRestrictions(device.getFcmToken());
            }
        }

        List<String> blockedPackages = appRestrictionRepository
                .findByAdminIdAndForceInstall(restriction.getAdminId(), false)
                .stream().map(AppRestriction::getPackageName).toList();
        List<String> forceInstallPackages = appRestrictionRepository
                .findByAdminIdAndForceInstall(restriction.getAdminId(), true)
                .stream().map(AppRestriction::getPackageName).toList();
        amapiService.applyPolicy(blockedPackages, forceInstallPackages);

        return ResponseEntity.ok(restriction);
    }

    @DeleteMapping("/api/restrictions")
    public ResponseEntity<?> unblockApp(@RequestParam Long adminId,
                                        @RequestParam String packageName) {
        appRestrictionRepository.deleteByAdminIdAndPackageName(adminId, packageName);

        List<EnrolledDevice> devices = enrolledDeviceRepository.findByAdminId(adminId);
        for (EnrolledDevice device : devices) {
            if (device.getFcmToken() != null && !device.getFcmToken().isEmpty()) {
                fcmService.sendEnforceRestrictions(device.getFcmToken());
            }
        }

        List<String> blockedPackages = appRestrictionRepository
                .findByAdminIdAndForceInstall(adminId, false)
                .stream().map(AppRestriction::getPackageName).toList();
        List<String> forceInstallPackages = appRestrictionRepository
                .findByAdminIdAndForceInstall(adminId, true)
                .stream().map(AppRestriction::getPackageName).toList();
        amapiService.applyPolicy(blockedPackages, forceInstallPackages);

        return ResponseEntity.ok("App unblocked");
    }

    // ── AMAPI Force Install ───────────────────────────────────────────────

    @PostMapping("/api/amapi/force-install")
    public ResponseEntity<?> forceInstallApp(@RequestParam Long adminId,
                                             @RequestParam String packageName) {
        try {
            if (appRestrictionRepository.existsByAdminIdAndPackageName(adminId, packageName)) {
                return ResponseEntity.badRequest().body("Package already exists in policy");
            }

            AppRestriction forceInstall = new AppRestriction();
            forceInstall.setAdminId(adminId);
            forceInstall.setPackageName(packageName);
            forceInstall.setCreatedAt(LocalDateTime.now());
            forceInstall.setForceInstall(true);
            appRestrictionRepository.save(forceInstall);

            List<String> blockedPackages = appRestrictionRepository
                    .findByAdminIdAndForceInstall(adminId, false)
                    .stream().map(AppRestriction::getPackageName).toList();
            List<String> forceInstallPackages = appRestrictionRepository
                    .findByAdminIdAndForceInstall(adminId, true)
                    .stream().map(AppRestriction::getPackageName).toList();

            amapiService.applyPolicy(blockedPackages, forceInstallPackages);

            return ResponseEntity.ok("App force install initiated via AMAPI");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed: " + e.getMessage());
        }
    }

    @DeleteMapping("/api/amapi/force-install")
    public ResponseEntity<?> removeForceInstall(@RequestParam Long adminId,
                                                @RequestParam String packageName) {
        try {
            appRestrictionRepository.deleteByAdminIdAndPackageName(adminId, packageName);

            List<String> blockedPackages = appRestrictionRepository
                    .findByAdminIdAndForceInstall(adminId, false)
                    .stream().map(AppRestriction::getPackageName).toList();
            List<String> forceInstallPackages = appRestrictionRepository
                    .findByAdminIdAndForceInstall(adminId, true)
                    .stream().map(AppRestriction::getPackageName).toList();

            amapiService.applyPolicy(blockedPackages, forceInstallPackages);

            return ResponseEntity.ok("Force install removed");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed: " + e.getMessage());
        }
    }

    @GetMapping("/api/amapi/force-install")
    public ResponseEntity<?> getForceInstalledApps(@RequestParam Long adminId) {
        List<AppRestriction> list = appRestrictionRepository
                .findByAdminIdAndForceInstall(adminId, true);
        return ResponseEntity.ok(list);
    }

    // ── AMAPI Enrollment Token ────────────────────────────────────────────
    @PostMapping("/api/amapi/enrollment-token")
    public ResponseEntity<?> createAmapiEnrollmentToken(@RequestParam Long adminId) {
        try {
            String token = amapiService.createEnrollmentToken();
            if (token == null) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("Failed to create AMAPI enrollment token");
            }
            Map<String, String> result = new HashMap<>();
            result.put("token", token);
            result.put("enterpriseId", amapiService.getEnterpriseId());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error: " + e.getMessage());
        }
    }
}
