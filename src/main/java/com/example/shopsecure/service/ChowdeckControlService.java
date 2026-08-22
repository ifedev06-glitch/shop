package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.UpdateChowdeckControlRequest;
import com.example.shopsecure.dto.response.PageResponse;
import com.example.shopsecure.dto.response.admin.AdminChowdeckControlHistoryResponse;
import com.example.shopsecure.dto.response.admin.AdminChowdeckControlResponse;
import com.example.shopsecure.entity.*;
import com.example.shopsecure.exception.ConflictException;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.repository.ChowdeckControlHistoryRepository;
import com.example.shopsecure.repository.ChowdeckControlRepository;
import com.example.shopsecure.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ChowdeckControlService {

    private static final long CONTROL_ID = 1L;

    private final ChowdeckControlRepository controlRepository;
    private final ChowdeckControlHistoryRepository historyRepository;
    private final UserRepository userRepository;

    @Value("${shipping.chowdeck.hard-disabled:true}")
    private boolean hardDisabled;

    @Transactional(readOnly = true)
    public Decision decision() {
        ChowdeckControl control = controlRepository.findById(CONTROL_ID).orElse(null);
        ChowdeckMode configured = control == null ? ChowdeckMode.OFF : control.getConfiguredMode();
        return new Decision(
                configured,
                hardDisabled ? ChowdeckMode.OFF : configured,
                hardDisabled,
                control == null ? 0 : control.getVersion()
        );
    }

    @Transactional(readOnly = true)
    public AdminChowdeckControlResponse get() {
        ChowdeckControl control = requireControl();
        return response(control);
    }

    @Transactional
    public AdminChowdeckControlResponse update(UpdateChowdeckControlRequest request) {
        if (request == null || request.mode() == null || request.expectedVersion() == null) {
            throw new BusinessException("Mode and expected version are required");
        }
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.isBlank() || reason.length() > 500) {
            throw new BusinessException("Change reason must contain 1 to 500 characters");
        }
        User admin = currentAdmin();
        ChowdeckControl control = controlRepository.findSingletonForUpdate()
                .orElseThrow(() -> new ResourceNotFoundException("Chowdeck control was not initialized"));
        if (control.getVersion() != request.expectedVersion()) {
            throw new ConflictException("Chowdeck control changed; refresh and retry");
        }
        if (control.getConfiguredMode() == request.mode()) {
            return response(control);
        }

        ChowdeckMode fromMode = control.getConfiguredMode();
        Instant now = Instant.now();
        control.setConfiguredMode(request.mode());
        control.setUpdatedBy(admin);
        control.setUpdatedAt(now);
        control = controlRepository.save(control);
        controlRepository.flush();
        historyRepository.save(ChowdeckControlHistory.builder()
                .fromMode(fromMode)
                .toMode(request.mode())
                .reason(reason)
                .changedBy(admin)
                .changedAt(now)
                .controlVersion(control.getVersion())
                .build());
        return response(control);
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminChowdeckControlHistoryResponse> history(int page, int size) {
        if (page < 0) {
            throw new BusinessException("Page number cannot be negative");
        }
        if (size < 1 || size > 100) {
            throw new BusinessException("Page size must be between 1 and 100");
        }
        return PageResponse.from(historyRepository
                .findAllByOrderByControlVersionDesc(PageRequest.of(page, size))
                .map(this::historyResponse));
    }

    private AdminChowdeckControlResponse response(ChowdeckControl control) {
        ChowdeckMode effective = hardDisabled ? ChowdeckMode.OFF : control.getConfiguredMode();
        return new AdminChowdeckControlResponse(
                control.getConfiguredMode(),
                effective,
                hardDisabled,
                control.getVersion(),
                control.getUpdatedBy() == null ? null : control.getUpdatedBy().getId(),
                control.getUpdatedAt()
        );
    }

    private AdminChowdeckControlHistoryResponse historyResponse(ChowdeckControlHistory history) {
        return new AdminChowdeckControlHistoryResponse(
                history.getId(),
                history.getFromMode(),
                history.getToMode(),
                history.getReason(),
                history.getChangedBy().getId(),
                history.getChangedAt(),
                history.getControlVersion()
        );
    }

    private ChowdeckControl requireControl() {
        return controlRepository.findById(CONTROL_ID)
                .orElseThrow(() -> new ResourceNotFoundException("Chowdeck control was not initialized"));
    }

    private User currentAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication.getName() == null) {
            throw new AccessDeniedException("Admin authentication is required");
        }
        User user = userRepository.findByEmail(authentication.getName().trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new AccessDeniedException("Admin authentication is required"));
        if (user.getRole() != Role.ADMIN || !user.isEnabled()) {
            throw new AccessDeniedException("Admin authentication is required");
        }
        return user;
    }

    public record Decision(
            ChowdeckMode configuredMode,
            ChowdeckMode effectiveMode,
            boolean hardKillActive,
            long version
    ) {
        public boolean enabledForNewShipments() {
            return effectiveMode == ChowdeckMode.ON;
        }

        public boolean shadowEnabled() {
            return effectiveMode == ChowdeckMode.SHADOW;
        }
    }
}
