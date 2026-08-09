package com.example.shopsecure.service;

import com.example.shopsecure.provider.payment.Bank;
import com.example.shopsecure.provider.payment.BankAccountLookupRequest;
import com.example.shopsecure.provider.payment.PayoutProvider;
import com.example.shopsecure.provider.payment.ResolvedBankAccount;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BankAccountServiceTest {

    @Test
    void reusesRecentResolutionForTheFinalCounterpartySubmission() {
        PayoutProvider payoutProvider = mock(PayoutProvider.class);
        BankAccountLookupRequest lookup = new BankAccountLookupRequest("0123456789", "999");
        when(payoutProvider.listBanks()).thenReturn(List.of(new Bank("Test Bank", "999")));
        when(payoutProvider.resolveBankAccount(lookup)).thenReturn(new ResolvedBankAccount(
                "Test Vendor",
                "0123456789",
                "999",
                null
        ));
        BankAccountService service = new BankAccountService(payoutProvider);

        ResolvedBankAccount first = service.resolve(lookup.accountNumber(), lookup.bankCode());
        ResolvedBankAccount repeated = service.resolve(lookup.accountNumber(), lookup.bankCode());

        assertThat(first).isEqualTo(repeated);
        assertThat(repeated.bankName()).isEqualTo("Test Bank");
        verify(payoutProvider, times(1)).resolveBankAccount(lookup);
    }
}
