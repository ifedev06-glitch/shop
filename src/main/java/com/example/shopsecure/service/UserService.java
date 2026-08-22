package com.example.shopsecure.service;

import com.example.shopsecure.dto.response.*;
import com.example.shopsecure.entity.User;
import com.example.shopsecure.entity.VirtualAccount;
import com.example.shopsecure.entity.Wallet;
import com.example.shopsecure.repository.VirtualAccountRepository;
import com.example.shopsecure.repository.WalletRepository;
import com.example.shopsecure.dto.request.UpdateProfileRequest;
import com.example.shopsecure.provider.payment.VirtualAccountProvider;
import com.example.shopsecure.provider.payment.VirtualAccountRequest;
import com.example.shopsecure.repository.UserRepository;
import com.example.shopsecure.util.NigerianPhoneNumber;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final OrderService orderService;
    private final WalletRepository walletRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final UserRepository userRepository;
    private final VirtualAccountProvider virtualAccountProvider;

    @Transactional(readOnly = true)
    public CurrentUserResponse getCurrentUser() {
        User user = orderService.currentUser();
        Wallet wallet = walletRepository.findByUser_Id(user.getId()).orElse(null);
        VirtualAccount account = virtualAccountRepository.findByUser_Id(user.getId()).orElse(null);
        return new CurrentUserResponse(
                user.getId(),
                user.getFirstname(),
                user.getLastname(),
                user.getEmail(),
                user.getPhoneNumber(),
                user.getRole(),
                wallet == null ? null : new WalletResponse(
                        wallet.getBalance(),
                        wallet.getCurrency(),
                        wallet.getUpdatedAt()
                ),
                account == null ? null : new VirtualAccountDetailsResponse(
                        account.getAccountNumber(),
                        account.getBankName(),
                        account.getAccountName(),
                        account.getExpiresAt()
                )
        );
    }

    @Transactional
    public CurrentUserResponse updateCurrentUser(UpdateProfileRequest request) {
        User user = orderService.currentUser();
        user.setFirstname(request.firstname().trim());
        user.setLastname(request.lastname().trim());
        user.setPhoneNumber(NigerianPhoneNumber.normalize(request.phoneNumber()));
        userRepository.save(user);
        if (virtualAccountRepository.findByUser_Id(user.getId()).isEmpty()) {
            var issued = virtualAccountProvider.createVirtualAccount(new VirtualAccountRequest(
                    user.getFirstname() + " " + user.getLastname(),
                    user.getEmail(),
                    user.getPhoneNumber(),
                    "user-" + user.getId()
            ));
            virtualAccountRepository.save(VirtualAccount.builder()
                    .user(user)
                    .accountNumber(issued.accountNumber())
                    .bankName(issued.bankName())
                    .accountName(issued.accountName())
                    .expiresAt(issued.expiresAt())
                    .createdAt(Instant.now())
                    .build());
        }
        return getCurrentUser();
    }
}
