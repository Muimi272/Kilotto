package club.muimi.kilotto.auth.security;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("kilotto.limits")
public record RequestLimits(@DefaultValue("60") @Min(1) int loginsPerMinute,
                            @DefaultValue("600") @Min(1) int upgradesPerMinute,
                            @DefaultValue("5000") @Min(1) int connections,
                            @DefaultValue("16") @Min(1) int guestConnections) {}
