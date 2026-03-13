package com.mdm.backend.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "app_activity_log")
public class AppActivityLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String deviceId;
    private String model;
    private String manufacturer;
    private String appName;
    private String packageName;
    private String installSource;
    private String action;
    private LocalDateTime timestamp;
    private Long adminId;

    public Long getId() { return id; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String d) { this.deviceId = d; }
    public String getModel() { return model; }
    public void setModel(String m) { this.model = m; }
    public String getManufacturer() { return manufacturer; }
    public void setManufacturer(String m) { this.manufacturer = m; }
    public String getAppName() { return appName; }
    public void setAppName(String a) { this.appName = a; }
    public String getPackageName() { return packageName; }
    public void setPackageName(String p) { this.packageName = p; }
    public String getInstallSource() { return installSource; }
    public void setInstallSource(String s) { this.installSource = s; }
    public String getAction() { return action; }
    public void setAction(String a) { this.action = a; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime t) { this.timestamp = t; }
    public Long getAdminId() { return adminId; }
    public void setAdminId(Long a) { this.adminId = a; }
}