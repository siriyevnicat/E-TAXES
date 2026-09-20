package az.gmb.taxdata.auth;

import java.time.Instant;

public record AuthenticatedUser(
        String id,
        String username,
        String whatsapp,
        String role,
        String workspaceId,
        String etaxesPhone,
        String etaxesUserId,
        String etaxesTin,
        Instant profileUpdatedAt,
        Instant accessStartAt,
        Instant accessEndAt
) {
    public boolean isAdmin() { return "ADMIN".equalsIgnoreCase(role); }
}
