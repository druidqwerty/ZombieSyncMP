#!/usr/bin/env bash
# Builds ZombieSyncMP.jar, assembles dist/ZombieSyncMP (mod folder) and runs the offline simulator.
set -euo pipefail
cd "$(dirname "$0")/.."

JDK="${JDK:-C:/Users/druid/.openclaw/workspace/_ext/jdk/jdk-25.0.4.1+1/bin}"
GAME="${GAME:-G:/Steam/steamapps/common/ProjectZomboid}"
CP="$GAME/projectzomboid.jar;$GAME/ZombieBuddy.jar"
MODVER="${MODVER:-1.0.0}"

rm -rf build/classes build/sim
mkdir -p build/classes build/sim

"$JDK/javac" --release 25 -encoding UTF-8 -Xlint:all,-serial,-processing -cp "$CP" -d build/classes src/com/druid/zombiesync/*.java
printf 'Implementation-Title: ZombieSyncMP\nImplementation-Version: %s\n' "$MODVER" > build/manifest.txt
"$JDK/jar" --create --file build/ZombieSyncMP.jar --manifest build/manifest.txt -C build/classes .

# offline simulator: only the game-independent classes
"$JDK/javac" --release 25 -encoding UTF-8 -d build/sim \
  src/com/druid/zombiesync/Track.java src/com/druid/zombiesync/Sample.java src/com/druid/zombiesync/Config.java \
  src/com/druid/zombiesync/Stamp.java test/com/druid/zombiesync/SimTest.java

rm -rf dist/ZombieSyncMP
mkdir -p dist/ZombieSyncMP/common dist/ZombieSyncMP/42/media/java
cp build/ZombieSyncMP.jar dist/ZombieSyncMP/42/media/java/ZombieSyncMP.jar
cp 42/mod.info dist/ZombieSyncMP/42/mod.info
sed -i "s/^modversion=.*/modversion=$MODVER/" dist/ZombieSyncMP/42/mod.info
[ -f 42/poster.png ] && cp 42/poster.png dist/ZombieSyncMP/42/poster.png
mkdir -p dist/ZombieSyncMP/42/src
cp -r src/com dist/ZombieSyncMP/42/src/

sha256sum build/ZombieSyncMP.jar
if [ "${1:-}" != "--no-sim" ]; then
  "$JDK/java" -cp build/sim com.druid.zombiesync.SimTest
fi
