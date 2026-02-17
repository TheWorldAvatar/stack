#!/usr/bin/bash

TOKEN=$(./curl_for_token_dev.sh | jq -r '.access_token')

curl -v -H "Authorization: Bearer $TOKEN" http://localhost:1916/whoami/