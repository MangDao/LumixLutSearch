# 发布到 GitHub — 操作指引

仓库已经准备好了（在 `D:\devenv\proj\LumixLutSearch`）：

- ✅ 已 `git init` 并完成首次提交 `e6ab9f8`，分支已改为 `main`
- ✅ `.gitignore` 挡住 `local.properties`（含你的本机 SDK 路径）、`.gradle/`、`build/`
- ✅ `.gitattributes` 钉住换行符 —— **这一条很关键，见文末**
- ✅ 已补 `gradlew` / `gradlew.bat` / `gradle/wrapper/`（原本没有，别人 clone 下来没法构建）
- ✅ `README.md`（含 8 张截图）、`LICENSE`（MIT）
- ✅ 已 clone 验证：40 个文件逐一 SHA256 一致，缺的只有 `local.properties`

---

## 第 0 步：先设 git 身份（**你现在没设**）

我查过了，`user.name` 和 `user.email` 都是空的。不设的话提交作者是错的。

```bash
git config --global user.name "你的名字"
git config --global user.email "你的邮箱@example.com"
```

> 邮箱建议用 GitHub 提供的隐私邮箱（形如 `12345678+用户名@users.noreply.github.com`），
> 在 GitHub → Settings → Emails 里能查到，可以避免真实邮箱进提交历史。

---

## 第 1 步：在 GitHub 上建仓库

打开 https://github.com/new

| 字段 | 填什么 |
|---|---|
| Repository name | `LumixLutSearch`（或你喜欢的名字） |
| Description | `LUMIX Lab 社区 LUT 的浏览 / 筛选 / 下载工具（非官方）` |
| Public / Private | 看你，**Public** 才能让别人也用到 |
| **Initialize this repository with** | **全部不要勾**（不要 README、不要 .gitignore、不要 License） |

> 这一步**必须留空**。你已经有了 README / .gitignore / LICENSE，
> 如果 GitHub 也生成一份，push 时会冲突（`refusing to merge unrelated histories`）。

建完页面会显示仓库地址，形如：

```
https://github.com/<你的用户名>/LumixLutSearch.git
```

---

## 第 2 步：关联并推送

在 `D:\devenv\proj\LumixLutSearch` 目录下：

```bash
git remote add origin https://github.com/<你的用户名>/LumixLutSearch.git
git push -u origin main
```

**如果提示要密码**：GitHub 早已不支持账号密码，需要 **Personal Access Token**：

1. https://github.com/settings/tokens → Generate new token (classic)
2. 勾选 **`repo`** 权限
3. 把生成的 token 当密码填进去

或者改用 SSH（一次配好，以后不用输）：

```bash
ssh-keygen -t ed25519 -C "你的邮箱"
# 把 ~/.ssh/id_ed25519.pub 的内容贴到 https://github.com/settings/keys
git remote set-url origin git@github.com:<你的用户名>/LumixLutSearch.git
git push -u origin main
```

---

## 第 3 步（可选）：发 Release 放 APK

**不要把 APK 提交进仓库** —— 二进制文件会让仓库越来越臃肿，而且每次改代码都要重存一份。
GitHub Releases 是专门放构建产物的。

```bash
git tag -a v2.2 -m "v2.2"
git push origin v2.2
```

然后到 `https://github.com/<用户名>/LumixLutSearch/releases/new`：
- Choose a tag 选 `v2.2`
- 标题填 `v2.2`
- 把 `LUT-Search-v2.2-debug.apk` 拖进附件区
- 说明里贴上 README 里的更新记录

---

## 关于「首次构建很慢」

别人（以及你自己换台机器）第一次跑 `./gradlew` 时，wrapper 会去
`services.gradle.org` 下载 Gradle 8.7 发行包（约 130 MB）。
**国内直连这个地址常常很慢甚至卡死** —— 我在验证时它就卡在 0 MB。

解决办法（改本机的 `gradle-wrapper.properties`，**不要提交这个改动**）：

```properties
# 腾讯云镜像
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip
```

或改用本地已有的 Gradle（你机器上就有）：

```bash
D:\devenv\gradle-8.7\bin\gradle.bat assembleDebug
```

另外，Android 依赖本身走的是阿里云镜像
（`settings.gradle` 里已配好 `maven.aliyun.com`），这块不用管。

---

## 为什么 `.gitattributes` 是必需的

这是我在准备仓库时发现并修掉的一个坑。

`gradlew` 是 **shell 脚本**。Windows 上如果没有 `.gitattributes`，
git 默认会把它的 LF 转成 CRLF 再入库，于是 **Linux / macOS 用户 clone 后执行会直接报**：

```
bad interpreter: /bin/sh^M: no such file or directory
```

我的 `.gitattributes` 按扩展名钉死了：

```
gradlew   text eol=lf      # shell 脚本，必须 LF
*.bat     text eol=crlf    # Windows 批处理，必须 CRLF
*.png     binary           # 图片别碰
```

提交后 `git add` **不再有 CRLF 警告**，说明生效了。

---

## 之后改代码怎么发

```bash
cd D:\devenv\proj\LumixLutSearch
git add -A
git commit -m "修复：xxx"
git push
```

---

## 附：这次交付里没进仓库的文件

| 文件 | 为什么没进 |
|---|---|
| `local.properties` | 含本机 SDK 路径，每台机器不同 |
| `app/build/`、`.gradle/` | 构建产物，能重新生成 |
| `LUT-Search-v2.2-debug.apk` | 二进制产物，走 Releases 而不是提交 |
| `为什么导入的LUT没有详情按钮.md` | 独立分析文档，不属于代码仓库（想放可以把 `docs/` 里） |
