package com.example.kladdo.service;

import com.example.kladdo.dto.LoginRequest;
import com.example.kladdo.dto.LoginResponse;
import com.example.kladdo.dto.UserDto;
import com.example.kladdo.exception.ResourceNotFoundException;
import com.example.kladdo.model.User;
import com.example.kladdo.repository.UserRepository;
import com.example.kladdo.security.CustomUserDetails;
import com.example.kladdo.security.JwtService;
import com.example.kladdo.security.SecurityUtil;
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

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password()));

        CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
        String token = jwtService.generateToken(userDetails);

        User user = userRepository.findById(userDetails.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        user.getWarehouses().size(); // init lazy collection for inclusion in response
        return new LoginResponse(token, UserDto.fromWithWarehouses(user, permissionService.permissionMapFor(user)));
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public UserDto currentUser() {
        User user = userRepository.findById(SecurityUtil.currentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        user.getWarehouses().size();
        return UserDto.fromWithWarehouses(user, permissionService.permissionMapFor(user));
    }
}
