package com.example.tenderapp.service;

import com.example.tenderapp.dto.LoginRequest;
import com.example.tenderapp.dto.LoginResponse;
import com.example.tenderapp.dto.UserDto;
import com.example.tenderapp.exception.ResourceNotFoundException;
import com.example.tenderapp.model.User;
import com.example.tenderapp.repository.UserRepository;
import com.example.tenderapp.security.CustomUserDetails;
import com.example.tenderapp.security.JwtService;
import com.example.tenderapp.security.SecurityUtil;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final PermissionService permissionService;

    public AuthService(AuthenticationManager authenticationManager,
                       JwtService jwtService,
                       UserRepository userRepository,
                       PermissionService permissionService) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.permissionService = permissionService;
    }

    public LoginResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password()));

        CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
        String token = jwtService.generateToken(userDetails);

        User user = userRepository.findByEmail(userDetails.getEmail())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return new LoginResponse(token, UserDto.from(user, permissionService.permissionMapFor(user)));
    }

    public UserDto currentUser() {
        User user = userRepository.findById(SecurityUtil.currentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return UserDto.from(user, permissionService.permissionMapFor(user));
    }
}
