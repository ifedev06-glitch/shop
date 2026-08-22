package com.example.shopsecure.integration;

import com.example.shopsecure.config.RateLimitFilter;
import com.example.shopsecure.entity.OtpPurpose;
import com.example.shopsecure.provider.notification.SmsProvider;
import com.example.shopsecure.provider.notification.StubSmsProvider;
import com.example.shopsecure.provider.payment.PaymentProvider;
import com.example.shopsecure.provider.payment.PaystackPaymentProvider;
import com.example.shopsecure.provider.payment.PayoutProvider;
import com.example.shopsecure.provider.payment.StubPaymentProvider;
import com.example.shopsecure.provider.shipping.ShippingProvider;
import com.example.shopsecure.provider.shipping.StubShippingProvider;
import com.example.shopsecure.service.EmailService;
import com.example.shopsecure.service.OtpService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:loadtestprofile;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@ActiveProfiles("loadtest")
class LoadTestProfileIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private PaymentProvider paymentProvider;

    @Autowired
    private PayoutProvider payoutProvider;

    @Autowired
    private ShippingProvider shippingProvider;

    @Autowired
    private SmsProvider smsProvider;

    @Autowired
    private EmailService emailService;

    @Autowired
    private OtpService otpService;

    @Autowired
    private RateLimitFilter rateLimitFilter;

    @Test
    void selectsOnlyInProcessProvidersAndDisablesRateLimiting() {
        assertThat(paymentProvider).isInstanceOf(StubPaymentProvider.class);
        assertThat(payoutProvider).isSameAs(paymentProvider);
        assertThat(shippingProvider).isInstanceOf(StubShippingProvider.class);
        assertThat(smsProvider).isInstanceOf(StubSmsProvider.class);
        assertThat(applicationContext.getBeansOfType(PaystackPaymentProvider.class)).isEmpty();
        assertThat(ReflectionTestUtils.getField(rateLimitFilter, "enabled")).isEqualTo(false);
    }

    @Test
    void suppressesEmailAndUsesTheKnownLoadTestOtp() {
        assertThatCode(() -> emailService.sendEmail(
                "nobody@example.invalid",
                "Load test",
                "This must not leave the process"
        )).doesNotThrowAnyException();
        assertThatCode(() -> emailService.sendEmailWithAttachment(
                "nobody@example.invalid",
                "Load test",
                "This must not leave the process",
                new byte[]{1, 2, 3},
                "test.pdf"
        )).doesNotThrowAnyException();

        String subject = "loadtest:" + UUID.randomUUID();
        OtpService.IssuedOtp issued = otpService.issueEmail(
                OtpPurpose.EMAIL_VERIFICATION,
                subject,
                "nobody@example.invalid"
        );
        assertThat(issued.code()).isEqualTo("000000");
        assertThatCode(() -> otpService.verify(
                OtpPurpose.EMAIL_VERIFICATION,
                subject,
                issued.challengeId(),
                "000000"
        )).doesNotThrowAnyException();
    }
}
