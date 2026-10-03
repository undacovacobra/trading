#!/usr/bin/env bash
# Builds a signed release APK without Gradle or Android Studio.
#
# Needs: JDK 17+, aapt2, dalvik-exchange (dx), zipalign, apksigner
#   (Ubuntu/Debian: apt install aapt apksigner zipalign dalvik-exchange)
# plus two Robolectric "android-all" framework jars from Maven Central (downloaded on first run):
#   API 31 for linking resources (newer resource tables are too new for Debian's aapt2)
#   API 35 for compiling Java against the Android 15 SDK.
#
# Signing: ROAM_KEYSTORE=path ROAM_KEYSTORE_PASS=... ./build.sh
# Keep that keystore safe. Android only installs updates signed with the same key.
set -euo pipefail
cd "$(dirname "$0")"

TOOLS=${ROAM_TOOLS:-.tools}
OUT=build
MAVEN=https://repo.maven.apache.org/maven2
FW_RES=$TOOLS/android-all-12-robolectric-7732740.jar
FW_JAVA=$TOOLS/android-all-15-robolectric-13954326.jar
JUNIT=$TOOLS/junit-4.13.2.jar
HAMCREST=$TOOLS/hamcrest-core-1.3.jar
ORGJSON=$TOOLS/json-20240303.jar

fetch() { [ -f "$2" ] || { mkdir -p "$TOOLS"; echo "Downloading $(basename "$2")"; curl -fsSL -o "$2.part" "$MAVEN/$1" && mv "$2.part" "$2"; }; }
fetch org/robolectric/android-all/12-robolectric-7732740/android-all-12-robolectric-7732740.jar "$FW_RES"
fetch org/robolectric/android-all/15-robolectric-13954326/android-all-15-robolectric-13954326.jar "$FW_JAVA"
fetch junit/junit/4.13.2/junit-4.13.2.jar "$JUNIT"
fetch org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar "$HAMCREST"
fetch org/json/json/20240303/json-20240303.jar "$ORGJSON"

VERSION=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml)
grep -q "VERSION = \"$VERSION\"" src/app/roam/companion/BuildInfo.java \
  || { echo "BuildInfo.VERSION doesn't match versionName $VERSION" >&2; exit 1; }

rm -rf "$OUT" && mkdir -p "$OUT"/{gen,classes,test-classes}

echo "== Unit tests"
# Pure logic and the API normalizers. The Android framework jar is on the path only so classes that
# also talk to Android compile; tests call their pure methods. org.json comes first on the path.
LOGIC="DepartureDetector Home LocationPlan NudgePolicy Units Json Suggestions Curator PlaceTags GooglePlaces OsmPlaces Events Weather Keys FileCache Http HomeSearch BuildInfo"
LOGIC_SRC=$(for c in $LOGIC; do echo src/app/roam/companion/$c.java; done)
javac -nowarn --release 17 -d "$OUT/test-classes" -cp "$JUNIT:$ORGJSON:$FW_JAVA" $LOGIC_SRC $(find test -name '*.java')
java -cp "$OUT/test-classes:$JUNIT:$HAMCREST:$ORGJSON:$FW_JAVA" org.junit.runner.JUnitCore \
  $(cd test && find . -name '*Test.java' | sed 's|^\./||; s|\.java$||; s|/|.|g')

echo "== Resources"
aapt2 compile --dir res -o "$OUT/res.zip"
aapt2 link -I "$FW_RES" --manifest AndroidManifest.xml -A assets \
  --min-sdk-version 26 --target-sdk-version 35 \
  --java "$OUT/gen" -o "$OUT/unsigned.apk" "$OUT/res.zip"

echo "== Java"
# Java 8 bytecode without lambdas so Debian's dx can convert it.
javac -nowarn -Xlint:-options --release 8 -cp "$FW_JAVA" -d "$OUT/classes" \
  $(find src "$OUT/gen" -name '*.java')
dalvik-exchange --dex --min-sdk-version=26 --output="$OUT/classes.dex" "$OUT/classes"
(cd "$OUT" && zip -q -j unsigned.apk classes.dex)

echo "== Package"
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
: "${ROAM_KEYSTORE:?Set ROAM_KEYSTORE to your release keystore}"
: "${ROAM_KEYSTORE_PASS:?Set ROAM_KEYSTORE_PASS}"
apksigner sign --ks "$ROAM_KEYSTORE" --ks-pass env:ROAM_KEYSTORE_PASS --ks-key-alias "${ROAM_KEY_ALIAS:-roam}" \
  --out "$OUT/roam-$VERSION.apk" "$OUT/aligned.apk"
apksigner verify "$OUT/roam-$VERSION.apk"
echo "Built $OUT/roam-$VERSION.apk"
