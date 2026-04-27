package com.smartac.security;

import com.smartac.admin.model.AdminUser;
import com.smartac.admin.repo.AdminUserRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AdminUserDetailsService implements UserDetailsService {

  private final AdminUserRepository adminUserRepository;

  public AdminUserDetailsService(AdminUserRepository adminUserRepository) {
    this.adminUserRepository = adminUserRepository;
  }

  @Override
  public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    AdminUser admin =
        adminUserRepository
            .findByEmailIgnoreCase(username)
            .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
    if (admin.isBlocked()) {
      throw new UsernameNotFoundException("Account is blocked");
    }
    return User.withUsername(admin.getEmail())
        .password(admin.getPasswordHash())
        .disabled(!admin.isEnabled())
        .roles("ADMIN")
        .build();
  }
}
