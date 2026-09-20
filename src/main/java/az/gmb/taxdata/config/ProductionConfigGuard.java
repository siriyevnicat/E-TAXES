package az.gmb.taxdata.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;

@Component
public class ProductionConfigGuard {
    private final Environment env;
    private final String dbUrl;
    private final String dbUser;
    private final String dbPassword;
    private final String adminPassword;

    public ProductionConfigGuard(Environment env,
                                 @Value("${spring.datasource.url:}") String dbUrl,
                                 @Value("${spring.datasource.username:}") String dbUser,
                                 @Value("${spring.datasource.password:}") String dbPassword,
                                 @Value("${app.admin.password:}") String adminPassword) {
        this.env = env;
        this.dbUrl = dbUrl;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        this.adminPassword = adminPassword;
    }

    @PostConstruct
    public void validate() {
        boolean prod = Arrays.stream(env.getActiveProfiles()).anyMatch("prod"::equalsIgnoreCase)
                || (env.getActiveProfiles().length == 0 && Arrays.stream(env.getDefaultProfiles()).anyMatch("prod"::equalsIgnoreCase));
        if (!prod) return;

        require(dbUrl, "SUPABASE_DB_URL");
        require(dbUser, "SUPABASE_DB_USER");
        require(dbPassword, "SUPABASE_DB_PASSWORD");
        require(adminPassword, "APP_ADMIN_PASSWORD");

        if (!dbUrl.startsWith("jdbc:postgresql://")) {
            throw new IllegalStateException("Production datasource PostgreSQL olmalıdır (jdbc:postgresql://...).");
        }

        String normalizedDbUrl = dbUrl.toLowerCase(Locale.ROOT);
        boolean encryptedTransport = normalizedDbUrl.contains("sslmode=require")
                || normalizedDbUrl.contains("sslmode=verify-ca")
                || normalizedDbUrl.contains("sslmode=verify-full");
        if (!encryptedTransport) {
            throw new IllegalStateException("Production PostgreSQL bağlantısı TLS ilə qorunmalıdır: SUPABASE_DB_URL daxilində sslmode=require (və ya verify-ca/verify-full) verin.");
        }

        if (adminPassword.length() < 14 || "ChangeMe@2026".equals(adminPassword) || "Admin123456!".equals(adminPassword)) {
            throw new IllegalStateException("APP_ADMIN_PASSWORD ən azı 14 simvol və güclü olmalıdır; default parol production-da qadağandır.");
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " production üçün mütləq verilməlidir.");
    }
}
