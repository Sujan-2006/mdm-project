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
@Table(name = "app_restriction")
public class AppRestriction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long adminId;
    private String packageName;
    private String appName;
    private LocalDateTime createdAt;

    @Column(name = "force_install", nullable = false)
    private boolean forceInstall = false;
}