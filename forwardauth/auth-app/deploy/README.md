# Traefik/OAuth2-Proxy Deployment on credo hosts

This script automates the deployment of the Traefik reverse proxy with OAuth2-Proxy authentication across multiple CReDO hosts.

## Supported Hosts

| Host | User | Traefik Port | Nginx Port | Short hostname |
|------|------|--------------|------------|------|
| credo-integration-01.dafni.rl.ac.uk | shared | 9050 | 8050 | ci01.credo |
| credo-datahost-01.dafni.rl.ac.uk | cadent | 9051 | 8051 | cd01.credo |
| credo-datahost-02.dafni.rl.ac.uk | ngt | 9051 | 8051 | cd02.credo |
| credo-datahost-03.dafni.rl.ac.uk | shared | 9050 | 8050 | cd03.credo |

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
```


```bash
# ci01.credo (auto-detected from credo-integration-01)
sudo -u shared bash -c 'cd ~ && rm -rf stack && git clone https://github.com/TheWorldAvatar/stack.git && cd stack && git checkout add-traefik-support && cd forwardauth/auth-app && ./deploy/deploy.sh'

# cd01.credo (auto-detected from credo-datahost-01)
sudo -u cadent bash -c 'cd ~ && rm -rf stack && git clone https://github.com/TheWorldAvatar/stack.git && cd stack && git checkout add-traefik-support && cd forwardauth/auth-app && ./deploy/deploy.sh'

# cd02.credo (auto-detected from credo-datahost-02)
sudo -u ngt bash -c 'cd ~ && rm -rf stack && git clone https://github.com/TheWorldAvatar/stack.git && cd stack && git checkout add-traefik-support && cd forwardauth/auth-app && ./deploy/deploy.sh'

# cd03.credo (auto-detected from credo-datahost-03)
sudo -u shared bash -c 'cd ~ && rm -rf stack && git clone https://github.com/TheWorldAvatar/stack.git && cd stack && git checkout add-traefik-support && cd forwardauth/auth-app && ./deploy/deploy.sh'
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
Enter Keycloak client secret for the 'traefik' client :
```

### 3. What the Script Does

1. **Detects/validates** the current host
2. **Reads secrets** for OAuth2 configuration
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
