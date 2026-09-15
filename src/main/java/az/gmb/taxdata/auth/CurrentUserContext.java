package az.gmb.taxdata.auth;

public final class CurrentUserContext {
    private static final ThreadLocal<AuthenticatedUser> CURRENT = new ThreadLocal<>();
    private CurrentUserContext() {}
    public static void set(AuthenticatedUser user) { CURRENT.set(user); }
    public static AuthenticatedUser get() { return CURRENT.get(); }
    public static AuthenticatedUser require() {
        AuthenticatedUser u = CURRENT.get();
        if (u == null) throw new SecurityException("Giriş tələb olunur.");
        return u;
    }
    public static void clear() { CURRENT.remove(); }
}
