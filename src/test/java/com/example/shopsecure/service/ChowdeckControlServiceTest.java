package com.example.shopsecure.service;

import com.example.shopsecure.dto.request.UpdateChowdeckControlRequest;
import com.example.shopsecure.entity.ChowdeckControl;
import com.example.shopsecure.entity.ChowdeckControlHistory;
import com.example.shopsecure.entity.ChowdeckMode;
import com.example.shopsecure.entity.Role;
import com.example.shopsecure.entity.User;
import com.example.shopsecure.exception.ConflictException;
import com.example.shopsecure.repository.ChowdeckControlHistoryRepository;
import com.example.shopsecure.repository.ChowdeckControlRepository;
import com.example.shopsecure.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChowdeckControlServiceTest {

    private ChowdeckControlRepository controlRepository;
    private ChowdeckControlHistoryRepository historyRepository;
    private UserRepository userRepository;
    private ChowdeckControlService service;
    private User admin;

    @BeforeEach
    void setUp() {
        controlRepository = mock(ChowdeckControlRepository.class);
        historyRepository = mock(ChowdeckControlHistoryRepository.class);
        userRepository = mock(UserRepository.class);
        service = new ChowdeckControlService(controlRepository, historyRepository, userRepository);
        ReflectionTestUtils.setField(service, "hardDisabled", false);
        admin = User.builder()
                .id(9L)
                .email("admin@example.com")
                .role(Role.ADMIN)
                .emailVerifiedAt(LocalDateTime.now())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        admin.getEmail(),
                        "n/a",
                        List.of()
                )
        );
        when(userRepository.findByEmail(admin.getEmail())).thenReturn(Optional.of(admin));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void missingControlFailsClosedForRoutingDecision() {
        when(controlRepository.findById(1L)).thenReturn(Optional.empty());

        ChowdeckControlService.Decision decision = service.decision();

        assertThat(decision.configuredMode()).isEqualTo(ChowdeckMode.OFF);
        assertThat(decision.effectiveMode()).isEqualTo(ChowdeckMode.OFF);
        assertThat(decision.enabledForNewShipments()).isFalse();
    }

    @Test
    void updatePersistsActorReasonAndIncrementedControlVersion() {
        ChowdeckControl control = control(ChowdeckMode.OFF, 3);
        when(controlRepository.findSingletonForUpdate()).thenReturn(Optional.of(control));
        when(controlRepository.save(control)).thenAnswer(invocation -> {
            control.setVersion(4);
            return control;
        });

        var response = service.update(new UpdateChowdeckControlRequest(
                ChowdeckMode.ON,
                3L,
                "Enable approved same-state rollout"
        ));

        assertThat(response.configuredMode()).isEqualTo(ChowdeckMode.ON);
        assertThat(response.effectiveMode()).isEqualTo(ChowdeckMode.ON);
        assertThat(response.version()).isEqualTo(4);
        assertThat(response.updatedByAdminId()).isEqualTo(admin.getId());
        ArgumentCaptor<ChowdeckControlHistory> history = ArgumentCaptor.forClass(
                ChowdeckControlHistory.class
        );
        verify(historyRepository).save(history.capture());
        assertThat(history.getValue().getFromMode()).isEqualTo(ChowdeckMode.OFF);
        assertThat(history.getValue().getToMode()).isEqualTo(ChowdeckMode.ON);
        assertThat(history.getValue().getReason()).isEqualTo("Enable approved same-state rollout");
        assertThat(history.getValue().getChangedBy()).isSameAs(admin);
        assertThat(history.getValue().getControlVersion()).isEqualTo(4);
    }

    @Test
    void staleAdminUpdateIsRejectedWithoutHistory() {
        when(controlRepository.findSingletonForUpdate()).thenReturn(Optional.of(
                control(ChowdeckMode.SHADOW, 8)
        ));

        assertThatThrownBy(() -> service.update(new UpdateChowdeckControlRequest(
                ChowdeckMode.ON,
                7L,
                "Stale switch"
        )))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Chowdeck control changed; refresh and retry");

        verify(controlRepository, never()).save(any());
        verify(historyRepository, never()).save(any());
    }

    @Test
    void hardKillOverridesConfiguredOnMode() {
        ReflectionTestUtils.setField(service, "hardDisabled", true);
        when(controlRepository.findById(1L)).thenReturn(Optional.of(control(ChowdeckMode.ON, 2)));

        ChowdeckControlService.Decision decision = service.decision();

        assertThat(decision.configuredMode()).isEqualTo(ChowdeckMode.ON);
        assertThat(decision.effectiveMode()).isEqualTo(ChowdeckMode.OFF);
        assertThat(decision.hardKillActive()).isTrue();
    }

    private ChowdeckControl control(ChowdeckMode mode, long version) {
        return ChowdeckControl.builder()
                .id(1L)
                .configuredMode(mode)
                .updatedAt(Instant.now())
                .version(version)
                .build();
    }
}
