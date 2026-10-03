# ADR 0005: API Security Boundary

## Decision
The reference implementation uses Spring Security role-based authorization. Production identity should come from an enterprise OIDC/OAuth2 provider.

## Roles
AML_OPERATOR submits transactions. AML_ANALYST reads investigation state.

The reference deployment uses HTTP Basic with environment-supplied credentials. Production should move to OIDC/OAuth2, service-to-service authentication, TLS, centralized secrets, and least-privilege authorization.
