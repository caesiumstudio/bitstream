#!/usr/bin/env zsh
# release.sh — Build a new BitStream release and publish it to GitHub

set -euo pipefail

print -n "Version name: "; read VERSION_NAME
print -n "Version code: "; read VERSION_CODE
APK_PATH="app/build/outputs/apk/release/app-release.apk"
TAG="v${VERSION_NAME}"

echo "==> Updating version in build.gradle.kts..."
sed -i '' \
  -e "s/versionCode = [0-9]*/versionCode = ${VERSION_CODE}/" \
  -e "s/versionName = \"[^\"]*\"/versionName = \"${VERSION_NAME}\"/" \
  app/build.gradle.kts

echo "==> Updating update.json..."
cat > update.json <<EOF
{
  "versionCode": ${VERSION_CODE},
  "versionName": "${VERSION_NAME}",
  "apkUrl": "https://github.com/caesiumstudio/bitstream/releases/latest/download/app-release.apk",
  "changelog": ""
}
EOF

echo "==> Building release APK..."
./gradlew clean assembleRelease --quiet

echo "==> Committing version bump..."
git add app/build.gradle.kts update.json
git commit -m "Release ${TAG}"
git push origin main

echo "==> Creating GitHub release and uploading APK..."
gh release create "${TAG}" "${APK_PATH}#app-release.apk" \
  --title "BitStream ${VERSION_NAME}" \
  --notes "" \
  --latest

echo "==> Done. Release ${TAG} is live."
echo "    APK: https://github.com/caesiumstudio/bitstream/releases/latest/download/app-release.apk"
