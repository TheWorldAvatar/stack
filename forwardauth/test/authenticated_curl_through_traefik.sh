#!/usr/bin/bash

TOKEN=$(./curl_for_token.sh | jq -r '.access_token')

curl -v -H "Authorization: Bearer $TOKEN" http://localhost:2025/whoami/