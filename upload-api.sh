#!/usr/bin/env bash
#
# upload-api.sh —— 通过 GitHub REST API 上传（无需 git push，可绕过网络阻断）
# 用法: ./upload-api.sh
#
set -e

USER_NAME="lsblockcoach"
REPO="quicktp"
BRANCH="main"
REPO_DIR="$HOME/snap/quicktp"
JAR_NAME="quicktp-1.0.0.jar"

cd "$REPO_DIR"

printf "粘贴 GitHub Token (ghp_...): "
read -r TOKEN
echo ""
[ -z "$TOKEN" ] && { echo "[!] Token 不能为空"; exit 1; }

AUTH="Authorization: Bearer $TOKEN"

upload() {  # $1=仓库内路径 $2=本地文件
    local path="$1" file="$2" sha="" body resp code
    # 查询已存在文件的 sha（更新时必须带上）
    sha=$(curl -s -H "$AUTH" "https://api.github.com/repos/$USER_NAME/$REPO/contents/$path?ref=$BRANCH" \
          | grep -o '"sha": "[a-f0-9]*"' | head -1 | cut -d'"' -f4)
    local b64
    b64=$(base64 -w0 "$file")
    if [ -n "$sha" ]; then
        resp=$(printf '{"message":"Update %s","content":"%s","branch":"%s","sha":"%s"}' \
               "$path" "$b64" "$BRANCH" "$sha" \
             | curl -s -X PUT -H "$AUTH" -d @- \
                   "https://api.github.com/repos/$USER_NAME/$REPO/contents/$path")
    else
        resp=$(printf '{"message":"Add %s","content":"%s","branch":"%s"}' \
               "$path" "$b64" "$BRANCH" \
             | curl -s -X PUT -H "$AUTH" -d @- \
                   "https://api.github.com/repos/$USER_NAME/$REPO/contents/$path")
    fi
    if echo "$resp" | grep -q '"commit"'; then
        echo "[✓] $path"
    else
        echo "[✗] $path"; echo "$resp" | head -c 300; echo ""; return 1
    fi
}

echo "== 开始上传到 github.com/$USER_NAME/$REPO =="

upload ".gitignore"                    ".gitignore"
upload "gradle.properties"             "gradle.properties"
upload "settings.gradle"               "settings.gradle"
upload "build.gradle"                  "build.gradle"
upload "src/main/java/com/example/quicktp/QuickTp.java" "src/main/java/com/example/quicktp/QuickTp.java"
upload "src/main/resources/fabric.mod.json"              "src/main/resources/fabric.mod.json"
upload "upload.sh"                     "upload.sh"
upload "$JAR_NAME"                     "build/libs/$JAR_NAME"

echo ""
echo "======================================="
echo " ✅ 完成！仓库: https://github.com/$USER_NAME/$REPO"
echo " jar 下载: https://github.com/$USER_NAME/$REPO/raw/main/$JAR_NAME"
echo "======================================="
