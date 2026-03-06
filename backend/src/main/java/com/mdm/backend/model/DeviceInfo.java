package com.mdm.backend.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "device_info")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id")
    private String deviceId;

    @Column(name = "model")
    private String model;

    @Column(name = "manufacturer")
    private String manufacturer;

    @Column(name = "os_version")
    private String osVersion;

    @Column(name = "sdk_version")
    private String sdkVersion;

    @Column(name = "uuid")
    private String uuid;

    @Column(name = "serial")
    private String serial;
}