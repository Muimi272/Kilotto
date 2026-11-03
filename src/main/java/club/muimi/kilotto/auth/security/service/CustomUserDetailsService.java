package club.muimi.kilotto.auth.security.service;
import club.muimi.kilotto.auth.dao.UserDao;
import club.muimi.kilotto.auth.security.entity.SecurityUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {
    private final UserDao userDao;
    @Override
    public UserDetails loadUserByUsername(String username) {
        return SecurityUser.from(userDao.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found")));
    }
}
