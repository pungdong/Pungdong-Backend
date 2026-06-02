package com.diving.pungdong.global.security;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

public class UserAccount extends User {
    private Account account;

    public UserAccount(Account account) {
        super(String.valueOf(account.getId()), account.getPassword(), authorities(account.getRoles()));
        this.account = account;
    }

    public static Collection<? extends GrantedAuthority> authorities(Set<Role> roles) {
        return roles.stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r.name()))
                .collect(Collectors.toList());
    }

    public Account getAccount() {
        return account;
    }
}
