#!/bin/bash

set -e

# Script to deploy Traefik/OAuth2-Proxy stack on different CReDO hosts
# Usage: ./deploy.sh [hostname]

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_DIR="$(dirname "$SCRIPT_DIR")"

# Function to print colored messages
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Host configuration table
declare -A HOST_USER
declare -A HOST_TRAEFIK_PORT
declare -A HOST_NGINX_PORT
declare -A HOST_FQDN
declare -A HOSTNAME_TO_SHORT

# Mapping from full hostname to short name
HOSTNAME_TO_SHORT["credo-integration-01"]="ci01.credo"
HOSTNAME_TO_SHORT["credo-integration-01.dafni.rl.ac.uk"]="ci01.credo"
HOSTNAME_TO_SHORT["credo-datahost-01"]="cd01.credo"
HOSTNAME_TO_SHORT["credo-datahost-01.dafni.rl.ac.uk"]="cd01.credo"
HOSTNAME_TO_SHORT["credo-datahost-02"]="cd02.credo"
HOSTNAME_TO_SHORT["credo-datahost-02.dafni.rl.ac.uk"]="cd02.credo"
HOSTNAME_TO_SHORT["credo-datahost-03"]="cd03.credo"
HOSTNAME_TO_SHORT["credo-datahost-03.dafni.rl.ac.uk"]="cd03.credo"

# ci01.credo configuration
HOST_USER["ci01.credo"]="shared"
HOST_TRAEFIK_PORT["ci01.credo"]="9050"
HOST_NGINX_PORT["ci01.credo"]="8050"
HOST_FQDN["ci01.credo"]="ci01.credo"

# cd01.credo configuration
HOST_USER["cd01.credo"]="cadent"
HOST_TRAEFIK_PORT["cd01.credo"]="9051"
HOST_NGINX_PORT["cd01.credo"]="8051"
HOST_FQDN["cd01.credo"]="cd01.credo"

# cd02.credo configuration
HOST_USER["cd02.credo"]="ngt"
HOST_TRAEFIK_PORT["cd02.credo"]="9051"
HOST_NGINX_PORT["cd02.credo"]="8051"
HOST_FQDN["cd02.credo"]="cd02.credo"

# cd03.credo configuration
HOST_USER["cd03.credo"]="shared"
HOST_TRAEFIK_PORT["cd03.credo"]="9050"
HOST_NGINX_PORT["cd03.credo"]="8050"
HOST_FQDN["cd03.credo"]="cd03.credo"

# Keycloak configuration (same for all hosts)
KEYCLOAK_REALM="CReDO"
KEYCLOAK_URL="https://idm-credo.hartree.app/realms/${KEYCLOAK_REALM}"
KEYCLOAK_CLIENT_ID="traefik"

# Detect current host or use provided argument
if [ -n "$1" ]; then
    CURRENT_HOST="$1"
else
    # Get the full hostname and map to short name
    FULL_HOSTNAME=$(hostname)
    CURRENT_HOST="${HOSTNAME_TO_SHORT[$FULL_HOSTNAME]}"
    
    # If not found in mapping, try using hostname directly
    if [ -z "$CURRENT_HOST" ]; then
        CURRENT_HOST="$FULL_HOSTNAME"
    fi
fi

log_info "Deploying for host: $CURRENT_HOST"

# Validate host
if [ -z "${HOST_USER[$CURRENT_HOST]}" ]; then
    log_error "Unknown host: $CURRENT_HOST"
    log_error "Valid hosts: ci01.credo, cd01.credo, cd02.credo, cd03.credo"
    exit 1
fi

# Get configuration for current host
DEPLOY_USER="${HOST_USER[$CURRENT_HOST]}"
TRAEFIK_PORT="${HOST_TRAEFIK_PORT[$CURRENT_HOST]}"
NGINX_PORT="${HOST_NGINX_PORT[$CURRENT_HOST]}"
FQDN="${HOST_FQDN[$CURRENT_HOST]}"
STACK_NAME="${CURRENT_HOST//./-}-stack"

log_info "Configuration:"
log_info "  User: $DEPLOY_USER"
log_info "  Traefik Port: $TRAEFIK_PORT"
log_info "  Nginx Port: $NGINX_PORT"
log_info "  FQDN: $FQDN"
log_info "  Stack Name: $STACK_NAME"

# Function to generate random string for secrets
generate_secret() {
    dd if=/dev/urandom bs=32 count=1 2>/dev/null | base64 | tr -d -- '\n' | tr -- '+/' '-_' ; echo
}

# Check if running as correct user
CURRENT_USER=$(whoami)
if [ "$CURRENT_USER" != "$DEPLOY_USER" ] && [ "$CURRENT_USER" != "root" ]; then
    log_warn "Current user ($CURRENT_USER) is not the deployment user ($DEPLOY_USER)"
    log_warn "Consider running as: sudo -u $DEPLOY_USER $0 $CURRENT_HOST"
fi

# Read or generate secrets
log_info "Setting up secrets..."

# Try to read existing client secret from .env if it exists
if [ -f "$BASE_DIR/.env" ]; then
    log_info "Reading existing .env file for secrets..."
    source "$BASE_DIR/.env" 2>/dev/null || true
fi

# If CLIENT_SECRET is not set, prompt for it
if [ -z "$CLIENT_SECRET" ]; then
    log_warn "CLIENT_SECRET not found in existing .env"
    while [ -z "$CLIENT_SECRET" ]; do
        read -rsp "Enter Keycloak client secret for '$KEYCLOAK_CLIENT_ID': " CLIENT_SECRET
        echo
        if [ -z "$CLIENT_SECRET" ]; then
            log_error "CLIENT_SECRET is required. Please enter a valid secret."
        fi
    done
fi

# Generate other secrets if not present
if [ -z "$ENCRYPTION_KEY" ]; then
    ENCRYPTION_KEY=$(generate_secret 32)
fi

if [ -z "$SIGNING_SECRET" ]; then
    SIGNING_SECRET=$(generate_secret 32)
fi

if [ -z "$COOKIE_SECRET" ]; then
    COOKIE_SECRET=$(generate_secret 32)
fi

# Create .env file
log_info "Writing .env file..."
cat > "$BASE_DIR/.env" << EOF
# Keycloak OAuth2 Configuration
CLIENT_ID=$KEYCLOAK_CLIENT_ID
CLIENT_SECRET=$CLIENT_SECRET
PROVIDER_URI=$KEYCLOAK_URL

# Security Secrets
ENCRYPTION_KEY=$ENCRYPTION_KEY
SIGNING_SECRET=$SIGNING_SECRET
COOKIE_SECRET=$COOKIE_SECRET

# Stack Configuration
STACK_NAME=$STACK_NAME
HOST_FQDN=$FQDN

# Port Configuration
TRAEFIK_PORT=$TRAEFIK_PORT
NGINX_PORT=$NGINX_PORT
EOF

log_info ".env file created"

# Create oauth2-proxy.cfg from template
log_info "Generating oauth2-proxy.cfg..."
cat > "$BASE_DIR/oauth2-proxy/oauth2-proxy.cfg" << EOF
#traefik
reverse_proxy="true" # are we running behind a reverse proxy

# oauth2-proxy
http_address="0.0.0.0:4180" #listen on all IPv4 interfaces

upstreams=["static://202"]
email_domains="*"

# Keycloak provider
provider="keycloak-oidc"
provider_display_name="CReDO Keycloak"
client_secret="$CLIENT_SECRET"
client_id="$KEYCLOAK_CLIENT_ID"
oidc_issuer_url="$KEYCLOAK_URL"
redirect_url="$FQDN"
scope="openid email profile groups"
code_challenge_method="S256"
insecure_oidc_allow_unverified_email="true"

# Cookies
cookie_secret="$COOKIE_SECRET"
cookie_secure="false"
cookie_samesite="lax"
whitelist_domains=["$FQDN","localhost:$TRAEFIK_PORT","127.0.0.1:$TRAEFIK_PORT"]
skip_jwt_bearer_tokens="true"
extra_jwt_issuers=["$KEYCLOAK_URL=$KEYCLOAK_CLIENT_ID"]

# Logging
request_logging="true"
auth_logging="true"
standard_logging="true"
skip_auth_strip_headers="false"

# Headers
set_xauthrequest="true"
set_authorization_header="true"
EOF

log_info "oauth2-proxy.cfg created"

# Update compose.yml with correct Traefik port
log_info "Updating compose.yml with Traefik port..."
sed -i.bak "s/\"[0-9]*:80\"/\"$TRAEFIK_PORT:80\"/" "$BASE_DIR/compose.yml"
log_info "compose.yml updated (Traefik port: $TRAEFIK_PORT:80)"

# Update dynamic.yml with correct Nginx port
log_info "Updating dynamic.yml with Nginx port..."
sed -i.bak "s|http://host.containers.internal:[0-9]*|http://host.containers.internal:$NGINX_PORT|" "$BASE_DIR/traefik/dynamic.yml"
log_info "dynamic.yml updated (Nginx port: $NGINX_PORT)"

# Set XDG_RUNTIME_DIR for podman if not set and fix podman socket
if [ -z "$XDG_RUNTIME_DIR" ]; then
    export XDG_RUNTIME_DIR="/run/user/$(id -u)"
    # hack to fix bad podman state
    podman system migrate
    # use a socket without systemd
    podman system service --time 5

fi

log_info "XDG_RUNTIME_DIR: $XDG_RUNTIME_DIR"

# Check if podman-compose is available
if ! command -v podman-compose &> /dev/null; then
    log_error "podman-compose not found. Please install it first."
    exit 1
fi

# Stop existing containers if running
log_info "Stopping existing containers..."
cd "$BASE_DIR"
podman-compose down 2>/dev/null || log_warn "No existing containers to stop"

# Start the stack
log_info "Starting auth stack..."
podman-compose up -d

# Check if containers started successfully
sleep 3
if podman ps | grep -q "standalone-traefik"; then
    log_info "${GREEN}✓${NC} Traefik container is running"
else
    log_error "Traefik container failed to start"
fi

if podman ps | grep -q "oauth2-proxy"; then
    log_info "${GREEN}✓${NC} OAuth2-Proxy container is running"
else
    log_error "OAuth2-Proxy container failed to start"
fi

# Display access information
log_info ""
log_info "=========================================="
log_info "Deployment Complete!"
log_info "=========================================="
log_info "Traefik Dashboard: http://localhost:8080"
log_info "Traefik Entry: http://localhost:$TRAEFIK_PORT"
log_info "Protected Service: http://localhost:$TRAEFIK_PORT/whoami"
log_info "Public Service: http://localhost:$TRAEFIK_PORT/whoami-public"
log_info ""
log_info "External Access: https://$FQDN"
log_info ""
log_info "Stack Name: $STACK_NAME"
log_info "=========================================="
log_info ""
log_info "To view logs:"
log_info "  podman-compose logs -f"
log_info ""
log_info "To stop the stack:"
log_info "  podman-compose down"
