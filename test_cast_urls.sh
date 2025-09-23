#!/bin/bash

# Test script to verify Audiobookshelf public session URLs for Cast compatibility
# Usage: ./test_cast_urls.sh <server_url> <session_id>

SERVER_URL="${1:-https://audiobookshelf.awsomefox.com}"
SESSION_ID="${2:-04966abf-deab-4fd9-9155-9dbcd4999a8b}"
TRACK_INDEX="${3:-1}"

echo "🧪 Testing Audiobookshelf Cast URL Compatibility"
echo "================================================"
echo "Server: $SERVER_URL"
echo "Session: $SESSION_ID"
echo "Track: $TRACK_INDEX"
echo ""

# Test URL that Android is generating
TEST_URL="$SERVER_URL/public/session/$SESSION_ID/track/$TRACK_INDEX"

echo "🔍 Testing URL: $TEST_URL"
echo ""

# Test 1: Basic connectivity (HEAD request)
echo "📡 Test 1: HEAD request (what Cast receiver does first)"
echo "------------------------------------------------------"
curl -I -s -w "HTTP Status: %{http_code}\nTotal Time: %{time_total}s\nContent Type: %{content_type}\nContent Length: %{size_download}\n" \
     -H "Origin: https://cast.receiver" \
     -H "Access-Control-Request-Method: GET" \
     "$TEST_URL" | head -20

echo ""

# Test 2: CORS preflight
echo "🌐 Test 2: CORS preflight request"
echo "--------------------------------"
curl -I -s -X OPTIONS \
     -H "Origin: https://cast.receiver" \
     -H "Access-Control-Request-Method: GET" \
     -H "Access-Control-Request-Headers: Content-Type" \
     "$TEST_URL" | grep -E "(HTTP|Access-Control|Allow)"

echo ""

# Test 3: Actual GET request (first few bytes)
echo "📥 Test 3: GET request (first 1KB of content)"
echo "--------------------------------------------"
curl -s -r 0-1023 \
     -H "Origin: https://cast.receiver" \
     -H "Range: bytes=0-1023" \
     -w "HTTP Status: %{http_code}\nContent Type: %{content_type}\nContent Length: %{size_download}\n" \
     "$TEST_URL" | head -5

echo ""

# Test 4: Check if it's audio content
echo "🎵 Test 4: Audio content verification"
echo "------------------------------------"
CONTENT_TYPE=$(curl -s -I "$TEST_URL" | grep -i "content-type:" | cut -d' ' -f2- | tr -d '\r')
echo "Content-Type: $CONTENT_TYPE"

if [[ "$CONTENT_TYPE" =~ audio/ ]]; then
    echo "✅ Valid audio content type detected"
else
    echo "⚠️  Not an audio content type - may cause Cast issues"
fi

echo ""

# Test 5: Server info
echo "📊 Test 5: Server information"
echo "----------------------------"
echo "Testing server ping..."
PING_RESULT=$(curl -s "$SERVER_URL/ping" | head -1)
echo "Ping response: $PING_RESULT"

echo ""
echo "🏁 Test completed!"
echo ""
echo "💡 For Cast to work properly:"
echo "   1. All tests should return HTTP 200"
echo "   2. CORS headers should include 'Access-Control-Allow-Origin: *'"
echo "   3. Content-Type should be audio/* (like audio/mpeg)"
echo "   4. Content should be accessible without authentication"
echo ""
echo "🔧 If tests fail, check your Audiobookshelf server configuration:"
echo "   - Ensure CORS is enabled for Cast receivers"
echo "   - Verify public session URLs are accessible"
echo "   - Check firewall settings"
