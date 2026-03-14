package com.mdm.backend.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "device_location")
public class DeviceLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String deviceId;
    private Long adminId;
    private Double latitude;
    private Double longitude;
    private Float accuracy;
    private String model;
    private String manufacturer;
    private LocalDateTime timestamp;

    public Long getId() { return id; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String d) { this.deviceId = d; }
    public Long getAdminId() { return adminId; }
    public void setAdminId(Long a) { this.adminId = a; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double lat) { this.latitude = lat; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double lon) { this.longitude = lon; }
    public Float getAccuracy() { return accuracy; }
    public void setAccuracy(Float acc) { this.accuracy = acc; }
    public String getModel() { return model; }
    public void setModel(String m) { this.model = m; }
    public String getManufacturer() { return manufacturer; }
    public void setManufacturer(String m) { this.manufacturer = m; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime t) { this.timestamp = t; }
}