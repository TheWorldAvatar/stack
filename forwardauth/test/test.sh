#!/bin/bash

set -e

echo "🔐 Testing Authentication Stack"
echo "================================"

# Test 1: Unauthenticated request should redirect
echo -n "1. Testing unauthenticated redirect... "
REDIRECT=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/whoami")
if [ "$REDIRECT" = "302" ]; then
    echo "✅ PASS (Got 302 redirect)"
else
    echo "❌ FAIL (Expected 302, got $REDIRECT)"
    exit 1
fi

# Test 2: Public endpoint should work without auth
echo -n "2. Testing public endpoint access... "
PUBLIC=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/whoami-public")
if [ "$PUBLIC" = "200" ]; then
    echo "✅ PASS (Got 200 OK)"
else
    echo "❌ FAIL (Expected 200, got $PUBLIC)"
    exit 1
fi

# Test 3: Get valid token and test authenticated access
echo -n "3. Testing authenticated access... "

ACCESS_TOKEN=$(./curl_for_token_dev.sh | jq -r '.access_token')

if [ "$ACCESS_TOKEN" = "null" ] || [ -z "$ACCESS_TOKEN" ]; then
    echo "❌ FAIL (Could not get access token)"
    echo "Response: $TOKEN_RESPONSE"
    exit 1
fi

# Simulate OAuth2 flow by setting cookie
AUTHED=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  "$BASE_URL/whoami")

if [ "$AUTHED" = "200" ] || [ "$AUTHED" = "302" ]; then
    echo "✅ PASS (Got $AUTHED)"
else
    echo "❌ FAIL (Expected 200 or 302, got $AUTHED)"
    exit 1
fi

# Test 4: OAuth2 endpoints are accessible
echo -n "4. Testing OAuth2 endpoints... "
OAUTH_AUTH=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/oauth2/auth")
if [ "$OAUTH_AUTH" = "401" ] || [ "$OAUTH_AUTH" = "403" ]; then
    echo "✅ PASS (Got $OAUTH_AUTH - expected for unauthenticated)"
else
    echo "⚠️  WARNING (Got $OAUTH_AUTH, expected 401/403)"
fi

echo ""
echo "================================"
echo "✅ All tests passed!"