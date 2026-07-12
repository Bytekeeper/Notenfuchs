#!/bin/sh
# Registers Notenfuchs as an OIDC client in Pocket ID (idempotent) and
# prints a fresh single-use sign-up link on every run. Runs once per
# `docker compose up` as the pocket-id-bootstrap service; see the README's
# "Setting up Pocket ID" section for what to do with its output.
set -eu

POCKET_ID_URL="http://pocket-id:1411"
BASE_URL="${BASE_URL:-localhost}"
CLIENT_ID="notenfuchs"

api() {
  method="$1"
  path="$2"
  body="${3:-}"
  if [ -n "$body" ]; then
    curl -sS -X "$method" -H "X-API-KEY: $STATIC_API_KEY" -H "Content-Type: application/json" \
      -d "$body" "$POCKET_ID_URL$path"
  else
    curl -sS -X "$method" -H "X-API-KEY: $STATIC_API_KEY" "$POCKET_ID_URL$path"
  fi
}

json_field() {
  # extracts "key":"value" from a flat JSON object on stdin
  grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4
}

client_status=$(curl -sS -o /dev/null -w '%{http_code}' -H "X-API-KEY: $STATIC_API_KEY" \
  "$POCKET_ID_URL/api/oidc/clients/$CLIENT_ID")

if [ "$client_status" = "200" ]; then
  echo "pocket-id-bootstrap: OIDC client '$CLIENT_ID' already registered, skipping."
else
  api POST /api/oidc/clients "{\"id\":\"$CLIENT_ID\",\"name\":\"Notenfuchs\",\"callbackURLs\":[\"http://$BASE_URL:8080/auth-callback\"],\"logoutCallbackURLs\":[\"http://$BASE_URL:8080/\"]}" >/dev/null

  secret=$(api POST "/api/oidc/clients/$CLIENT_ID/secret" | json_field secret)

  echo ""
  echo "=================================================================="
  echo " Notenfuchs OIDC client registered in Pocket ID."
  echo ""
  echo " Add this to your .env, then run 'docker compose up -d app':"
  echo ""
  echo "   OIDC_CLIENT_SECRET=$secret"
  echo "=================================================================="
  echo ""
fi

token=$(api POST /api/signup-tokens '{"ttl":"24h","usageLimit":1,"userGroupIds":[]}' | json_field token)

echo ""
echo "=================================================================="
echo " One-time sign-up link (single use, expires in 24h). Hand this to"
echo " the teacher who should create their Notenfuchs account - you"
echo " don't need to share your own Pocket ID admin login:"
echo ""
echo "   http://$BASE_URL:1411/st/$token"
echo "=================================================================="
echo ""
