# ADR 0005: API Security Boundary

## Decision
The reference implementation uses Spring Security role-based authorization. Production identity should come from an enterprise OIDC/OAuth2 provider.

## Roles
AML_OPERATOR submits transactions. AML_ANALYST reads investigation state.
