package com.mdm.backend.controller;

import com.mdm.backend.model.AppInventory;
import com.mdm.backend.model.DeviceInfo;
import com.mdm.backend.model.EnrolledDevice;
import com.mdm.backend.repository.AppInventoryRepository;
import com.mdm.backend.repository.DeviceInfoRepository;
import com.mdm.backend.repository.EnrolledDeviceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@CrossOrigin(origins = "*")
public class MdmController {

    @Autowired
    private EnrolledDeviceRepository enrolledDeviceRepository;

    @Autowired
    private DeviceInfoRepository deviceInfoRepository;

    @Autowired
    private AppInventoryRepository appInventoryRepository;

    // ─── POST Endpoints (Android App) ───────────────────────────

    @PostMapping("/enroll")
    public ResponseEntity<String> enrollDevice(
            @RequestBody EnrolledDevice device) {
        device.setEnrolledAt(java.time.LocalDateTime.now());
        enrolledDeviceRepository.save(device);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body("Device enrolled successfully");
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
        appInventoryRepository.saveAll(apps);
        return ResponseEntity.ok("App inventory saved");
    }

    // ─── GET Endpoints (Web Dashboard) ──────────────────────────

    @GetMapping("/api/devices")
    public ResponseEntity<List<EnrolledDevice>> getAllDevices() {
        return ResponseEntity.ok(enrolledDeviceRepository.findAll());
    }

    @GetMapping("/api/devices/{deviceId}/info")
    public ResponseEntity<?> getDeviceInfo(
            @PathVariable String deviceId) {
        try {
            return deviceInfoRepository.findByDeviceId(deviceId)
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.ok().build());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.OK)
                    .body(new java.util.HashMap<String, String>() {{
                        put("error", e.getMessage());
                    }});
        }
    }
    @GetMapping("/api/devices/{deviceId}/apps")
    public ResponseEntity<List<AppInventory>> getDeviceApps(
            @PathVariable String deviceId) {
        return ResponseEntity.ok(
                appInventoryRepository.findByDeviceId(deviceId));
    }

    @GetMapping("/api/stats")
    public ResponseEntity<?> getStats() {
        long totalDevices = enrolledDeviceRepository.count();
        long totalApps    = appInventoryRepository.count();
        return ResponseEntity.ok(
                new java.util.HashMap<String, Long>() {{
                    put("totalDevices", totalDevices);
                    put("totalApps", totalApps);
                }}
        );
    }
}