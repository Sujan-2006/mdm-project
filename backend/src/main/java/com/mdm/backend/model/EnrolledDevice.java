package com.mdm.backend.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EnrolledDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String deviceId;
    private String enrollmentToken;
    private LocalDateTime enrolledAt = LocalDateTime.now();

    @Column(name = "admin_id")
    private Long adminId;

    // Updated every time the device syncs — used for online/offline status
    @Column(name = "last_seen")
    private LocalDateTime lastSeen;

    @Column(name = "fcm_token")
    private String fcmToken;
}