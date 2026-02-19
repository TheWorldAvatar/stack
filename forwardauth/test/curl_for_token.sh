#!/bin/bash

# shellcheck source=.env
source .env

curl -s -X POST "${OIDC_TOKEN_URL}" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=password' \
  -d "client_id=${OIDC_CLIENT_ID}" \
  -d "client_secret=${OIDC_CLIENT_SECRET}" \
  -d "username=${USERNAME}" \
  -d "password=${PASSWORD}" \
  -d 'scope=openid'