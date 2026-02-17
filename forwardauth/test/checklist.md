# Pre-Production Security Checklist

## Authentication & Authorization
- [ ] Unauthenticated users are redirected to Keycloak
- [ ] Valid tokens grant access to protected resources
- [ ] Invalid/expired tokens are rejected
- [ ] Public endpoints remain accessible without authentication
- [ ] Session cookies have appropriate security flags (Secure, HttpOnly, SameSite)
- [ ] PKCE (S256) is enabled for OAuth2 flow

## Configuration Validation
- [ ] `COOKIE_SECURE=true` in production (HTTPS only)
- [ ] `COOKIE_DOMAIN` matches production domain
- [ ] `REDIRECT_URL` points to correct production URL
- [ ] `OIDC_ISSUER_URL` points to production Keycloak
- [ ] Client secret is stored securely (not in version control)
- [ ] Cookie secret is cryptographically random (32+ bytes)

## Network & Routing
- [ ] Traefik middleware is applied to all protected services
- [ ] OAuth2 endpoints (`/oauth2/*`) are publicly accessible
- [ ] ForwardAuth endpoint (`/oauth2/auth`) returns 401 for unauthenticated
- [ ] No sensitive endpoints are accidentally exposed
- [ ] Rate limiting is configured on authentication endpoints

## Error Handling
- [ ] 401/403 errors properly redirect to sign-in
- [ ] OAuth callback errors are logged
- [ ] Users see helpful error messages (not stack traces)
- [ ] Failed auth attempts are logged for monitoring

## Monitoring & Logging
- [ ] Authentication failures are logged
- [ ] OAuth2-proxy logs are captured and monitored
- [ ] Traefik access logs show authentication status
- [ ] Alerts configured for authentication service downtime

## Performance
- [ ] ForwardAuth requests complete in <100ms
- [ ] Sessions are cached appropriately
- [ ] No authentication loops detected
- [ ] Load testing completed with expected user count

## Rollback Plan
- [ ] Documentation for disabling authentication
- [ ] Backup of working configuration
- [ ] Process to revert to previous state
- [ ] Communication plan for users during issues

## Production Monitoring
Set up monitoring dashboards:

Authentication success/failure rates
OAuth2-proxy response times
Keycloak availability
Session cookie lifetimes
User error rates (401/403)