package com.mdm.backend;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

@Service
public class FCMService {

    @PostConstruct
    public void initialize() {
        try {
            if (FirebaseApp.getApps().isEmpty()) {
                String serviceAccountJson = System.getenv("FIREBASE_SERVICE_ACCOUNT");

                InputStream serviceAccount;

                if (serviceAccountJson != null && !serviceAccountJson.isEmpty()) {
                    // Environment variable
                    serviceAccount = new ByteArrayInputStream(
                            serviceAccountJson.getBytes(StandardCharsets.UTF_8)
                    );
                } else {
                    // Try Render secret file path first
                    java.io.File secretFile = new java.io.File(
                            "/etc/secrets/firebase-adminsdk.json");
                    if (secretFile.exists()) {
                        serviceAccount = new java.io.FileInputStream(secretFile);
                    } else {
                        // Local development fallback
                        serviceAccount = getClass().getClassLoader()
                                .getResourceAsStream("firebase-adminsdk.json");
                    }
                }

                if (serviceAccount == null) {
                    System.err.println("FCMService: No Firebase credentials found!");
                    return;
                }

                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                        .build();

                FirebaseApp.initializeApp(options);
                System.out.println("FCMService: Firebase initialized successfully!");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void sendEnforceRestrictions(String fcmToken) {
        try {
            Message message = Message.builder()
                    .putData("command", "ENFORCE_RESTRICTIONS")
                    .setToken(fcmToken)
                    .build();

            FirebaseMessaging.getInstance().send(message);
            System.out.println("FCMService: Push sent successfully to " + fcmToken);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}