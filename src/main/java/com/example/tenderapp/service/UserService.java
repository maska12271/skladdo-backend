package com.example.tenderapp.service;

import com.example.tenderapp.dto.CreateUserRequest;
import com.example.tenderapp.dto.UpdateUserRequest;
import com.example.tenderapp.dto.UserDto;
import com.example.tenderapp.exception.ForbiddenException;
import com.example.tenderapp.exception.ResourceNotFoundException;
import com.example.tenderapp.model.Company;
import com.example.tenderapp.model.Role;
import com.example.tenderapp.model.User;
import com.example.tenderapp.repository.CompanyRepository;
import com.example.tenderapp.repository.UserRepository;
import com.example.tenderapp.security.SecurityUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Manages user accounts within the caller's company. All lookups are scoped to the current
 * company so administrators can never read or modify another company's users.
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository,
                       CompanyRepository companyRepository,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.companyRepository = companyRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public List<UserDto> findAllForCurrentCompany() {
        return userRepository.findByCompanyIdOrderByIdDesc(SecurityUtil.currentCompanyId())
                .stream()
                .map(UserDto::from)
                .toList();
    }

    public UserDto create(CreateUserRequest request) {
        if (request.role() == Role.OWNER) {
            throw new ForbiddenException("Owner accounts cannot be created through user management");
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("An account with this email already exists");
        }

        Long companyId = SecurityUtil.currentCompanyId();
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company not found with id: " + companyId));

        User user = new User();
        user.setEmail(request.email());
        user.setFullName(request.fullName());
        user.setRole(request.role());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setCompany(company);
        user.setActive(true);
        user.setArchived(false);

        return UserDto.from(userRepository.save(user));
    }

    public UserDto update(Long id, UpdateUserRequest request) {
        User user = requireSameCompany(id);
        if (user.getRole() == Role.OWNER) {
            throw new ForbiddenException("The owner account cannot be modified");
        }
        if (request.role() == Role.OWNER) {
            throw new ForbiddenException("A user cannot be promoted to owner");
        }

        user.setFullName(request.fullName());
        user.setRole(request.role());

        if (request.password() != null && !request.password().isBlank()) {
            if (request.password().length() < 6) {
                throw new IllegalArgumentException("Password must be at least 6 characters");
            }
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }

        return UserDto.from(userRepository.save(user));
    }

    public void delete(Long id) {
        User user = requireSameCompany(id);
        guardNotSelf(user, "delete");
        if (user.getRole() == Role.OWNER) {
            throw new ForbiddenException("The owner account cannot be deleted");
        }
        userRepository.delete(user);
    }

    public UserDto setArchived(Long id, boolean archived) {
        User user = requireSameCompany(id);
        guardNotSelf(user, archived ? "archive" : "unarchive");
        if (user.getRole() == Role.OWNER) {
            throw new ForbiddenException("The owner account cannot be archived");
        }
        user.setArchived(archived);
        return UserDto.from(userRepository.save(user));
    }

    private User requireSameCompany(Long id) {
        return userRepository.findByIdAndCompanyId(id, SecurityUtil.currentCompanyId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));
    }

    private void guardNotSelf(User user, String action) {
        if (user.getId().equals(SecurityUtil.currentUserId())) {
            throw new ForbiddenException("You cannot " + action + " your own account");
        }
    }
}
