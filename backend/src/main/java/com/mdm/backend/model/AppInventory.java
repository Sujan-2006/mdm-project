package com.mdm.backend.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AppInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String deviceId;
    private String appName;
    private String packageName;
    private String versionName;
    private int versionCode;

    @JsonProperty("isSystemApp")
    @Column(name = "is_system_app")
    private Boolean isSystemApp;

    private String installSource;
}