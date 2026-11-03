package club.muimi.kilotto.auth.security.entity;
import club.muimi.kilotto.auth.entity.User;
import club.muimi.kilotto.auth.enums.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import java.util.Collection;
import java.util.List;

public record SecurityUser(Long id, String username, String password, Role role, boolean enabled, long authVersion)
        implements UserDetails {
    public static SecurityUser from(User user) {
        return new SecurityUser(user.getId(), user.getUsername(), user.getPassword(), user.getRole(), user.isEnabled(), user.getAuthVersion());
    }
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() { return List.of(role); }
    @Override
    public String getPassword() { return password; }
    @Override
    public String getUsername() { return username; }
    @Override
    public boolean isEnabled() { return enabled; }
    public boolean isAdmin() { return role == Role.ADMIN; }

    @Override
    public String toString() { return "SecurityUser[id=" + id + ", username=" + username + ", role=" + role + "]"; }
}
