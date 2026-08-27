#!/bin/bash
set -euo pipefail
rm -rf build
mkdir -p build/classes
find src -name '*.java' -print0 | xargs -0 javac --release 21 -d build/classes
cat > build/MANIFEST.MF <<'EOF'
Manifest-Version: 1.0
Main-Class: uk.blazecraft.novabroadcast.NovaBroadcast
EOF
jar cfm NovaBroadcast.jar build/MANIFEST.MF -C build/classes .
echo "Built NovaBroadcast.jar"
