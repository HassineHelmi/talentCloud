package com.talentcloud.auth.model;

import lombok.Data;
import lombok.Generated;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Data
@Table("users")
public class User {
    @Id
    private Long user_id;
    private String username;
    private String email;
    @Column("first_name")
    private String firstName;
    @Column("last_name")
    private String lastName;
    private Role role; // Assuming 'role' is an enum type defined elsewhere in your codebase
    @Column("keycloak_id")
    private String keycloakId;
}
