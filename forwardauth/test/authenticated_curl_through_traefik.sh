#!/usr/bin/bash

TOKEN=$(./curl_for_token.sh | jq -r '.access_token')

curl -v -w '\n' -H "Authorization: Bearer $TOKEN" http://localhost:9050/CentralStackAgent/getScenarios