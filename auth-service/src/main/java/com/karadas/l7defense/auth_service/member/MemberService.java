package com.karadas.l7defense.auth_service.member;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class MemberService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    public MemberService(MemberRepository memberRepository, PasswordEncoder passwordEncoder) {
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public Member register(String username, String rawPassword) {
        if (memberRepository.findByUsername(username).isPresent()) {
            throw new IllegalStateException("Username already taken");
        }
        String hashed = passwordEncoder.encode(rawPassword);
        return memberRepository.save(new Member(username, hashed));
    }

    public Optional<Member> authenticate(String username, String rawPassword) {
        return memberRepository.findByUsername(username)
                .filter(member -> passwordEncoder.matches(rawPassword, member.getPasswordHash()));
    }
}