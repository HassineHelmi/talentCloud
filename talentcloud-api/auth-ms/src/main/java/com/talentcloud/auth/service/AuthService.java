package com.talentcloud.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentcloud.auth.dto.LoginRequest;
import com.talentcloud.auth.dto.LogoutRequest;
import com.talentcloud.auth.dto.RegisterRequest;
import com.talentcloud.auth.exception.*;
import com.talentcloud.auth.model.Role;
import com.talentcloud.auth.model.User;
import com.talentcloud.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Mono;
import org.springframework.beans.factory.annotation.Value;
import reactor.core.scheduler.Schedulers;

import java.util.*;

@Service
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    @Value("${keycloak.client-id}")
    private String clientId;
    @Value("${keycloak.client-secret}")
    private String clientSecret;
    @Value("${KEYCLOAK_URL}")
    private String keycloakBaseUrl;
    @Value("${KEYCLOAK_REALM}")
    private String keycloakRealm;

    private String getTokenUrl() {
        return keycloakBaseUrl + "/realms/" + keycloakRealm + "/protocol/openid-connect/token";
    }

    private String getAdminUserUrl() {
        return keycloakBaseUrl + "/admin/realms/" + keycloakRealm + "/users";
    }

    private String getRolesUrl() {
        return keycloakBaseUrl + "/admin/realms/" + keycloakRealm + "/roles";
    }


    private final KeycloakService keycloakService;
    private final RestTemplate restTemplate;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public AuthService(KeycloakService keycloakService, RestTemplate restTemplate,
                       UserRepository userRepository, ObjectMapper objectMapper) {
        this.keycloakService = keycloakService;
        this.restTemplate = restTemplate;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    public Mono<ResponseEntity<String>> login(LoginRequest request) {
        return Mono.fromCallable(() -> {
            logger.info("Attempting login for user: {}", request.getUsername());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "password");
            body.add("client_id", clientId);
            body.add("client_secret", clientSecret);
            body.add("username", request.getUsername());
            body.add("password", request.getPassword());
            body.add("scope", "openid");

            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);

            logger.debug("Token request URL: {}", getTokenUrl());
            logger.debug("Token request body: {}", body);

            ResponseEntity<String> response = restTemplate.postForEntity(getTokenUrl(), entity, String.class);

            return ResponseEntity.ok(response.getBody());
        }).subscribeOn(Schedulers.boundedElastic());
    }


    public Mono<ResponseEntity<String>> register(RegisterRequest request) {
        logger.info("Starting user registration process for username: {}", request.getUsername());

        return userRepository.existsByUsername(request.getUsername())
                .flatMap(usernameExists -> {
                    if (usernameExists) {
                        return Mono.error(new UserAlreadyExistsException("Username already exists!"));
                    }

                    return userRepository.existsByEmail(request.getEmail());
                })
                .flatMap(emailExists -> {
                    if (emailExists) {
                        return Mono.error(new EmailAlreadyExistsException("Email already exists!"));
                    }

                    return Mono.fromCallable(() -> {
                                String adminToken = keycloakService.getAdminToken();
                                logger.info("Obtained admin token from Keycloak");

                                HttpHeaders headers = new HttpHeaders();
                                headers.setContentType(MediaType.APPLICATION_JSON);
                                headers.setBearerAuth(adminToken);
                                Map<String, Object> keycloakUser = new HashMap<>();
                                keycloakUser.put("username", request.getUsername());
                                keycloakUser.put("email", request.getEmail());
                                keycloakUser.put("enabled", true);
                                keycloakUser.put("firstName", request.getFirstName());
                                keycloakUser.put("lastName", request.getLastName());

                                Map<String, Object> credentials = Map.of(
                                        "type", "password",
                                        "value", request.getPassword(),
                                        "temporary", false
                                );
                                keycloakUser.put("credentials", List.of(credentials));

                                HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(keycloakUser, headers);
                                ResponseEntity<String> creationResponse = restTemplate.postForEntity(getAdminUserUrl(), requestEntity, String.class);
                                logger.info("User created successfully in Keycloak");

                                // *** MODIFICATION START ***
                                // Extract Keycloak user ID from the Location header
                                String keycloakUserId = null;
                                if (creationResponse.getHeaders().containsKey(HttpHeaders.LOCATION)) {
                                    String locationHeader = creationResponse.getHeaders().getFirst(HttpHeaders.LOCATION);
                                    if (locationHeader != null) {
                                        // The location header typically looks like:
                                        // http://<keycloak-host>:<port>/admin/realms/<realm>/users/<userId>
                                        // We need to extract the last segment which is the userId
                                        int lastSlashIndex = locationHeader.lastIndexOf('/');
                                        if (lastSlashIndex != -1 && lastSlashIndex < locationHeader.length() - 1) {
                                            keycloakUserId = locationHeader.substring(lastSlashIndex + 1);
                                        }
                                    }
                                }

                                if (keycloakUserId == null) {
                                    throw new KeycloakOperationException("Unable to retrieve user ID from Keycloak's Location header after creation.");
                                }
                                // *** MODIFICATION END ***


                                // Keycloak expects the role name as a String, so we use request.getRole().getRole()
                                String keycloakRoleName = request.getRole().getRole();

                                ResponseEntity<String> rolesResponse = restTemplate.exchange(
                                        getRolesUrl(),
                                        HttpMethod.GET,
                                        new HttpEntity<>(headers),
                                        String.class
                                );
                                String roleId = findRoleId(rolesResponse.getBody(), keycloakRoleName);
                                if (roleId == null) {
                                    throw new KeycloakOperationException("Role not found in Keycloak: " + keycloakRoleName);
                                }

                                // Assign role
                                String roleAssignmentUrl = getAdminUserUrl() + "/" + keycloakUserId + "/role-mappings/realm";
                                // Ensure the 'name' sent to Keycloak matches what Keycloak expects
                                List<Map<String, Object>> rolesList = List.of(Map.of("id", roleId, "name", keycloakRoleName));
                                HttpEntity<List<Map<String, Object>>> roleRequest = new HttpEntity<>(rolesList, headers);
                                restTemplate.postForEntity(roleAssignmentUrl, roleRequest, String.class);

                                logger.info("Role assigned successfully to user in Keycloak");

                                User user = new User();
                                user.setKeycloakId(keycloakUserId);
                                user.setUsername(request.getUsername());
                                user.setEmail(request.getEmail());
                                user.setFirstName(request.getFirstName());
                                user.setLastName(request.getLastName());
                                user.setRole(request.getRole());
                                return user;
                            })
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(user -> userRepository.save(user)
                                    .doOnSuccess(savedUser -> logger.info(
                                            "User saved successfully in DB with ID: {} and Username: {}",
                                            savedUser.getUser_id(), savedUser.getUsername()))
                                    .doOnError(error -> logger.error("Error saving user to DB: {}", error.getMessage(), error))
                                    .thenReturn(ResponseEntity.ok("User registered and role assigned successfully!")));
                });
    }


    public Mono<ResponseEntity<String>> logout(LogoutRequest request) {
        return Mono.fromCallable(() -> {
            logger.info("Logging out user with refresh token...");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("client_id", clientId);
            body.add("client_secret", clientSecret);
            body.add("refresh_token", request.getRefreshToken());

            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);

            restTemplate.postForEntity(
                    keycloakBaseUrl + "/realms/" + keycloakRealm + "/protocol/openid-connect/logout",
                    entity,
                    String.class
            );

            logger.info("User logged out successfully.");
            return ResponseEntity.ok("Logout successful.");
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // This method now expects a Role enum and converts it to its string representation
    private String findRoleId(String rolesResponseBody, String roleName) { // Kept roleName as String as it's the actual name from Keycloak
        JsonNode rolesArray;
        try {
            rolesArray = objectMapper.readTree(rolesResponseBody);
        } catch (Exception e) {
            throw new KeycloakRoleNotFoundException("Error parsing roles response: " + e.getMessage());
        }
        if (!rolesArray.isArray()) {
            throw new KeycloakRoleNotFoundException("Roles response is not an array: " + rolesResponseBody);
        }
        for (JsonNode role : rolesArray) {
            // Compare with the provided roleName string (e.g., "ROLE_ADMIN")
            if (role.has("name") && role.get("name").asText().equalsIgnoreCase(roleName)) {
                JsonNode idNode = role.get("id");
                if (idNode != null && !idNode.isNull()) {
                    return idNode.asText();
                }
            }
        }
        throw new KeycloakRoleNotFoundException("Requested role not found in Keycloak: " + roleName);
    }
}