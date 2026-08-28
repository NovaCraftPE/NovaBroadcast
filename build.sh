#!/bin/bash
set -euo pipefail
mvn -B -DskipTests clean package
cp target/NovaBroadcast.jar NovaBroadcast.jar
echo "Built NovaBroadcast.jar"
