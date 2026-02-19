# Traefik/OAuth2-Proxy Deployment Script

This script automates the deployment of the Traefik reverse proxy with OAuth2-Proxy authentication across multiple CReDO hosts.

## Supported Hosts

| Host | User | Traefik Port | Nginx Port | FQDN |
|------|------|--------------|------------|------|
| ci01.credo | shared | 9050 | 8050 | ci01.credo |
| cd01.credo | cadent | 9051 | 8051 | cd01.credo |
| cd02.credo | ngt | 9051 | 8051 | cd02.credo |
| cd03.credo | shared | 9050 | 8050 | cd03.credo |

The script automatically detects the hostname and maps:
- `credo-integration-01` → `ci01.credo`
- `credo-datahost-01` → `cd01.credo`
- `credo-datahost-02` → `cd02.credo`
- `credo-datahost-03` → `cd03.credo`

## Prerequisites

- Podman and podman-compose installed
- Access to the Keycloak admin console to retrieve/configure client secrets
- Appropriate user permissions on the target host

## Usage

### 1. Basic Deployment

Run the script without arguments to auto-detect the host, or specify a short hostname:

```bash
# Auto-detect current host from $(hostname)
./deploy.sh

# Or specify host explicitly
./deploy.sh ci01.credo
```

The script will automatically map full hostnames to short names:
- On `credo-integration-01`: auto-detects as `ci01.credo`
- On `credo-datahost-01`: auto-detects as `cd01.credo`
- On `credo-datahost-02`: auto-detects as `cd02.credo`
- On `credo-datahost-03`: auto-detects as `cd03.credo`

Run as the appropriate user for the host:

```bash
# Auto-detect current host
sudo -u <user> ./deploy.sh

# Or specify host explicitly
sudo -u <user> ./deploy.sh ci01.credo
```

Examples for each host:
```bash
# ci01.credo (auto-detected from credo-integration-01)
sudo -u shared ./deploy.sh

# cd01.credo (auto-detected from credo-datahost-01)
sudo -u cadent ./deploy.sh

# cd02.credo (auto-detected from credo-datahost-02)
sudo -u ngt ./deploy.sh

# cd03.credo (auto-detected from credo-datahost-03)
sudo -u shared ./deploy.sh
```

### 2. First-Time Setup

On first deployment, you'll be prompted for the Keycloak client secret:

```
Enter Keycloak client secret for 'traefik' (or press Enter to generate random):
```

**Important:** If you press Enter and a random secret is generated, you **must** configure this secret in Keycloak:
1. Log into Keycloak admin console at https://idm-credo.hartree.app
2. Navigate to: Realms → CReDO → Clients → traefik
3. Go to Credentials tab
4. Set the Client Secret to match the generated value (shown in `.env` file)

### 3. What the Script Does

1. **Detects/validates** the current host
2. **Generates or reads secrets** for OAuth2 configuration
3. **Creates `.env` file** with:
   - Keycloak client credentials
   - Generated security secrets
   - Host-specific configuration
4. **Generates `oauth2-proxy.cfg`** from template with:
   - Correct hostname/FQDN
   - Keycloak connection details
   - Cookie and JWT settings
5. **Updates `compose.yml`** to expose correct Traefik port
6. **Updates `traefik/dynamic.yml`** to proxy to correct Nginx port
7. **Stops any existing containers**
8. **Starts the stack** using podman-compose

## Configuration Files

After running the script, the following files will be created/updated:

- **`.env`** - Environment variables with secrets and configuration
- **`oauth2-proxy/oauth2-proxy.cfg`** - OAuth2-Proxy configuration
- **`compose.yml`** - Updated with correct Traefik port
- **`traefik/dynamic.yml`** - Updated with correct Nginx port

## Accessing the Services

After deployment:

- **Traefik Dashboard:** http://localhost:8080
- **Traefik Entry Point:** http://localhost:[TRAEFIK_PORT]
- **Protected Test Service:** http://localhost:[TRAEFIK_PORT]/whoami
- **Public Test Service:** http://localhost:[TRAEFIK_PORT]/whoami-public
- **External Access:** https://[FQDN]

## Managing the Stack

### View logs
```bash
cd /home/opeppard/repositories/stack/forwardauth/auth-app
podman-compose logs -f
```

### Check container status
```bash
podman ps
```

### Stop the stack
```bash
podman-compose down
```

### Restart the stack
```bash
podman-compose restart
```

### Full redeployment
```bash
./deploy/deploy.sh [hostname]
```

## Troubleshooting

### Containers not starting
1. Check podman logs: `podman-compose logs`
2. Verify ports are not already in use: `ss -tlnp | grep [PORT]`
3. Check SELinux context if applicable
4. Verify XDG_RUNTIME_DIR is set correctly

### Authentication not working
1. Verify Keycloak client secret matches in both Keycloak and `.env`
2. Check redirect URI in Keycloak matches the FQDN
3. Review oauth2-proxy logs: `podman-compose logs oauth2-proxy`
4. Ensure the FQDN is accessible and resolves correctly

### Permission issues
1. Ensure you're running as the correct user for the host
2. Check podman socket permissions
3. Verify file ownership in the deployment directory

## Security Notes

- The `.env` file contains sensitive secrets - keep it secure and don't commit to git
- Cookie secrets are used for session management
- Client secrets should be obtained from Keycloak admin
- All traffic should use HTTPS in production (configure in Traefik)

## Network Configuration

The stack creates/uses a network named `bongus` for inter-container communication.
All services should be on this network to communicate properly.

## Keycloak Configuration

Ensure the following settings in Keycloak for the `traefik` client:

- **Client ID:** traefik
- **Access Type:** confidential
- **Valid Redirect URIs:** 
  - https://ci01.credo/*
  - https://cd01.credo/*
  - https://cd02.credo/*
  - https://cd03.credo/*
  - http://localhost:[TRAEFIK_PORT]/*
- **Web Origins:** +
- **Client Protocol:** openid-connect

Note: The FQDNs now use the short hostnames from `/etc/hosts` (e.g., `ci01.credo`) rather than the full DAFNI domain names.
