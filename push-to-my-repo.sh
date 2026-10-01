# 把 DiAuto 源码推送到你的私有仓库
#
# 用法：
#   1. 在 GitHub 网页端新建一个**空的私有仓库**（不要勾 README / .gitignore / license）
#   2. 把下面的 YOUR_REPO_URL 换成你的仓库地址，例如：
#        https://github.com/zutaozhong-hash/DiAuto.git
#   3. 在本目录执行：  bash push-to-my-repo.sh https://github.com/<你>/<仓库名>.git

set -e

REPO_URL="$1"
if [ -z "$REPO_URL" ]; then
  echo "用法: bash push-to-my-repo.sh <你的私有仓库 git 地址>"
  exit 1
fi

cd "$(dirname "$0")"

echo "==> 当前远端"
git remote -v

# 把上游原仓库保留为 upstream，避免以后丢失参考
if ! git remote | grep -qx upstream; then
  git remote add upstream https://github.com/shihabal3amri/DiAuto.git
  echo "==> 已把原仓库登记为 upstream"
fi

# origin 指向你自己的私有仓库
if git remote | grep -qx origin; then
  git remote set-url origin "$REPO_URL"
else
  git remote add origin "$REPO_URL"
fi
echo "==> origin -> $REPO_URL"

echo "==> 推送前安全检查（应全部为空）"
git ls-files | grep -iE 'key\.properties|\.jks$|\.keystore$|secrets\.properties' || echo "  OK：没有签名/密钥文件被跟踪"

echo "==> 推送 main"
git push -u origin main

echo
echo "完成。以后同步只需："
echo "  git add -A && git commit -m '...' && git push"
