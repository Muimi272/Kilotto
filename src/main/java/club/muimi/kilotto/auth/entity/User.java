package club.muimi.kilotto.auth.entity;

import club.muimi.kilotto.auth.enums.Role;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 50)
    private String username;

    @Column(nullable = false, length = 100)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.USER;

    @Column(nullable = false)
    private boolean enabled = true;

    // A revocation survives re-enabling the account, including sessions that were idle while disabled.
    @Column(nullable = false, columnDefinition = "bigint default 0")
    private long authVersion;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
