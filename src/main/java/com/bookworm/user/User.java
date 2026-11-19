package com.bookworm.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * Account row. The login-lockout columns are mapped read-only ({@code updatable = false}) on purpose: they
 * are changed only by the atomic SQL in {@link UserRepository}, so an entity update can never overwrite a
 * concurrent failed-login count with a stale value. The role and {@code enabled} flag have no setters.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String email;

    private String passwordHash;

    private String firstName;

    private String lastName;

    private String phone;

    @Enumerated(EnumType.STRING)
    private Role role;

    private boolean enabled = true;

    @Column(insertable = false, updatable = false)
    private int failedLoginAttempts;

    @Column(insertable = false, updatable = false)
    private Instant lockedUntil;

    @Version
    private Long version;

    @Column(updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    protected User() {
    }

    static User create(String email, String passwordHash, String firstName, String lastName, String phone,
                       Role role) {
        User user = new User();
        user.email = email;
        user.passwordHash = passwordHash;
        user.firstName = firstName;
        user.lastName = lastName;
        user.phone = phone;
        user.role = role;
        user.createdAt = Instant.now();
        user.updatedAt = user.createdAt;
        return user;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void updateProfile(String firstName, String lastName, String phone) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.phone = phone;
    }

    void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    /** True while a lock is in force at {@code now}. */
    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getPhone() {
        return phone;
    }

    public Role getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
