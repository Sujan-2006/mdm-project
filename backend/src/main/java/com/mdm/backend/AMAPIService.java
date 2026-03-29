package com.mdm.backend;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.androidmanagement.v1.AndroidManagement;
import com.google.api.services.androidmanagement.v1.model.Policy;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AMAPIService {

    @Value("${amapi.enterprise.id}")
    private String enterpriseId;

    private AndroidManagement androidManagement;

    @PostConstruct
    public void initialize() {
        try {
            InputStream credentialsStream;

            String serviceAccountJson = System.getenv("AMAPI_SERVICE_ACCOUNT");
            if (serviceAccountJson != null && !serviceAccountJson.isEmpty()) {
                credentialsStream = new ByteArrayInputStream(
                        serviceAccountJson.getBytes(StandardCharsets.UTF_8));
            } else {
                File secretFile = new File("/etc/secrets/amapi-service-account.json");
                if (secretFile.exists()) {
                    credentialsStream = new FileInputStream(secretFile);
                } else {
                    credentialsStream = getClass().getClassLoader()
                            .getResourceAsStream("amapi-service-account.json");
                }
            }

            if (credentialsStream == null) {
                System.err.println("AMAPIService: No credentials found!");
                return;
            }

            GoogleCredentials credentials = GoogleCredentials
                    .fromStream(credentialsStream)
                    .createScoped(Collections.singletonList(
                            "https://www.googleapis.com/auth/androidmanagement"));

            androidManagement = new AndroidManagement.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials))
                    .setApplicationName("MDM Project")
                    .build();

            System.out.println("AMAPIService: Initialized successfully!");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void applyPolicy(List<String> blockedPackages,
                            List<String> forceInstalledPackages) {
        try {
            if (androidManagement == null) {
                System.err.println("AMAPIService: Not initialized!");
                return;
            }

            Policy policy = new Policy();
            List<Map<String, Object>> applications = new ArrayList<>();

            // Blocked apps
            for (String pkg : blockedPackages) {
                Map<String, Object> app = new HashMap<>();
                app.put("packageName", pkg);
                app.put("installType", "BLOCKED");
                applications.add(app);
            }

            // Force installed apps
            for (String pkg : forceInstalledPackages) {
                Map<String, Object> app = new HashMap<>();
                app.put("packageName", pkg);
                app.put("installType", "FORCE_INSTALLED");
                applications.add(app);
            }

            policy.set("applications", applications);

            String policyName = enterpriseId + "/policies/default";
            androidManagement.enterprises().policies()
                    .patch(policyName, policy)
                    .execute();

            System.out.println("AMAPIService: Policy applied! Blocked="
                    + blockedPackages.size() + " ForceInstall="
                    + forceInstalledPackages.size());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}