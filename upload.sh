#!/usr/bin/env bash
#
# upload.sh —— QuickTP 一键上传 GitHub 脚本
# 用法:  ./upload.sh   然后粘贴你的 Token (ghp_ 开头)
#
set -e

USER_NAME="lsblockcoach"
REPO="quicktp"
REPO_DIR="$HOME/snap/quicktp"
JAR_NAME="quicktp-1.0.0.jar"
JAR_SRC="$REPO_DIR/build/libs/$JAR_NAME"

echo "======================================="
echo " QuickTP -> github.com/$USER_NAME/$REPO"
echo "======================================="

# 1) 检查 jar 是否存在，不存在则尝试重新构建
if [ ! -f "$JAR_SRC" ]; then
    echo "[*] 没找到编译好的 jar，尝试重新构建..."
    if [ -x /tmp/gradle-9.5.1/bin/gradle ]; then
        (cd "$REPO_DIR" && /tmp/gradle-9.5.1/bin/gradle build --no-daemon -q)
    else
        echo "[!] 找不到 Gradle，请先手动构建"; exit 1
    fi
fi
echo "[✓] jar: $JAR_SRC"

# 2) 输入 Token（隐藏输入）
printf "粘贴 GitHub Token (ghp_...): "
read -r TOKEN
echo ""
if [ -z "$TOKEN" ]; then echo "[!] Token 不能为空"; exit 1; fi

# 3) 把 jar 复制到仓库根目录（最显眼的位置）
cp -f "$JAR_SRC" "$REPO_DIR/"

cd "$REPO_DIR"

# 4) 提交（源码 + jar）
git add -A
if git diff --cached --quiet; then
    echo "[*] 没有新的改动需要提交"
else
    git commit -m "Update QuickTP: source + compiled jar ($JAR_NAME)"
fi

# 5) 推送（临时使用 token，推完立即清除）
git remote set-url origin "https://$USER_NAME:$TOKEN@github.com/$USER_NAME/$REPO.git"
if git push -u origin main; then
    echo ""
    echo "======================================="
    echo " ✅ 上传成功！"
    echo " 仓库主页 : https://github.com/$USER_NAME/$REPO"
    echo " 直接下载 : https://github.com/$USER_NAME/$REPO/raw/main/$JAR_NAME"
    echo "======================================="
else
    echo "[!] 上传失败，请检查 Token 是否正确、是否勾选了 repo 权限"
fi

# 6) 从 remote URL 中清除 token（安全）
git remote set-url origin "https://github.com/$USER_NAME/$REPO.git"
