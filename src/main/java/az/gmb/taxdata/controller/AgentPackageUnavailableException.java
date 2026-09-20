package az.gmb.taxdata.controller;

public class AgentPackageUnavailableException extends RuntimeException {
    public AgentPackageUnavailableException(String message) { super(message); }
    public AgentPackageUnavailableException(String message, Throwable cause) { super(message, cause); }
}
