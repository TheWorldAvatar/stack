#!/usr/bin/bash

TOKEN=$(./curl_for_token.sh)

curl -v -w '\n' -H "Authorization: Bearer $TOKEN" http://localhost:2025/protected