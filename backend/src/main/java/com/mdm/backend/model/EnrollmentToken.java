package com.mdm.backend.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "enrollment_tokens")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EnrollmentToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token", unique = true, nullable = false)
    private String token;

    @Column(name = "label")
    private String label;

    @Column(name = "is_active")
    private boolean active = true;

    @Column(name = "max_uses")
    private int maxUses = 1;

    @Column(name = "current_uses")
    private int currentUses = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;
}