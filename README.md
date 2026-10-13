# 比色读数仪（安卓）

实时摄像头取景 → 拖动方框选中色块 → 读取选区平均 RGB → 按公式换算 pH 和细菌数量。

默认公式（App 里可直接改系数）：
- pH = 0.1 × R + 2
- 细菌数量 = 0.2 × R − 1

## 功能
- 实时取景，读数每 0.15 秒刷新
- 选框：框内拖动=移动，拖右下角圆点=缩放，框外拖动=重新画框
- 冻结画面 / 从相册选图，再精细调整选框
- 记录读数，导出 CSV（可发到微信、邮箱，用 Excel 打开）

## 方法一：用 GitHub 在云端编译（不用装任何软件）
1. 注册 GitHub 账号，新建一个仓库（Private 也可以）。
2. 点 “uploading an existing file”，把本文件夹里**所有文件和文件夹**拖进去上传
   （包括 `.github` 文件夹；如果网页上传不显示隐藏文件夹，在仓库里手动新建文件
   `.github/workflows/build-apk.yml`，把内容粘贴进去）。
3. 打开仓库的 **Actions** 页，等“编译 APK”跑完（约 5–8 分钟，变绿勾）。
4. 点进这次运行，页面底部 Artifacts 里下载 `比色读数仪-apk`，解压得到 `app-release.apk`。
5. 把 APK 发到手机安装（需允许“安装未知来源应用”）。

## 方法二：Android Studio
1. 安装 Android Studio，File → Open 选本文件夹，等 Gradle 同步完成。
2. 手机打开“开发者选项 → USB 调试”，连电脑，点绿色 ▶ 运行。
3. 或 Build → Build APK(s) 生成安装包。

## 代码位置
- `app/src/main/java/com/rgbph/meter/MainActivity.kt`：取色、公式、记录
- `app/src/main/java/com/rgbph/meter/SelectionOverlay.kt`：选框
- 改默认公式：搜索 `"0.1"`、`"2"`、`"0.2"`、`"-1"` 这几个默认值

## 测量建议
- 固定光源和拍摄距离，避免阴影和反光
- 先用已知 pH 的标准液拍几张，拟合出真实系数再填进 App
