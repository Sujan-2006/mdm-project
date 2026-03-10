package com.mdm.backend.controller;

import com.mdm.backend.model.Admin;
import com.mdm.backend.repository.AdminRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired
    private AdminRepository adminRepository;

    private final BCryptPasswordEncoder passwordEncoder =
            new BCryptPasswordEncoder();

    // ── Register ──
    @PostMapping("/register")
    public ResponseEntity<?> register(
            @RequestBody Map<String, String> body) {
        try {
            String username = body.get("username");
            String password = body.get("password");
            String email    = body.get("email");

            if (username == null || username.isEmpty() ||
                    password == null || password.isEmpty()) {
                return ResponseEntity
                        .status(HttpStatus.BAD_REQUEST)
                        .body("Username and password are required!");
            }

            if (adminRepository.existsByUsername(username)) {
                return ResponseEntity
                        .status(HttpStatus.CONFLICT)
                        .body("Username already exists!");
            }

            if (email != null && !email.isEmpty() &&
                    adminRepository.existsByEmail(email)) {
                return ResponseEntity
                        .status(HttpStatus.CONFLICT)
                        .body("Email already registered!");
            }

            Admin admin = new Admin();
            admin.setUsername(username);
            admin.setPassword(passwordEncoder.encode(password));
            admin.setEmail(email);
            admin.setCreatedAt(LocalDateTime.now());

            Admin saved = adminRepository.save(admin);

            Map<String, Object> response = new HashMap<>();
            response.put("id",       saved.getId());
            response.put("username", saved.getUsername());
            response.put("message",  "Registered successfully!");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Registration failed: " + e.getMessage());
        }
    }

    // ── Login ──
    @PostMapping("/login")
    public ResponseEntity<?> login(
            @RequestBody Map<String, String> body) {
        try {
            String username = body.get("username");
            String password = body.get("password");

            Optional<Admin> adminOpt =
                    adminRepository.findByUsername(username);

            if (adminOpt.isEmpty()) {
                return ResponseEntity
                        .status(HttpStatus.UNAUTHORIZED)
                        .body("Invalid username or password!");
            }

            Admin admin = adminOpt.get();

            if (!passwordEncoder.matches(
                    password, admin.getPassword())) {
                return ResponseEntity
                        .status(HttpStatus.UNAUTHORIZED)
                        .body("Invalid username or password!");
            }

            Map<String, Object> response = new HashMap<>();
            response.put("id",       admin.getId());
            response.put("username", admin.getUsername());
            response.put("message",  "Login successful!");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Login failed: " + e.getMessage());
        }
    }
}