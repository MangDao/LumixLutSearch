# fixtures —— GL 滤镜对拍素材（不进包）

开发期用来核对渲染链的测试素材，总共约 18 MB，所以**不放在源集里**：

- `app/src/main/assets` → 会被打进所有包
- `app/src/debug/assets` → 会被打进 debug 包（之前就是这么误进 18 MB 的）

| 文件 | 用来核对什么 |
|---|---|
| `std.jpg` | STD 直通基准（未套 LUT 的参考画面）|
| `vlog.jpg` | V-Log 编解码曲线（黑位 0.125 / 18% 灰 0.4233 / 白 0.5991）|
| `sample.jpg` | 通用样张（裁剪、相框、导出前跑一遍）|
| `bars.png` | 彩条（通道顺序、色偏、白平衡）|
| `gradient.png` | 灰阶渐变（色调曲线、banding、颗粒）|
| `teal.cube` | 已知 3D LUT（LUT 采样方向、四面体插值）|

## 需要时怎么用

`app/build.gradle` 里挂了一个开关，只有加 `-PwithFixtures` 才会把它们临时加入 debug 源集：

```bash
./gradlew assembleDebug -PwithFixtures
# Windows: gradlew.bat assembleDebug -PwithFixtures
```

不加开关时，打出来的包不含这些素材（`LUT-Search-v2.5-debug.apk` 约 24 MB；
加上则是约 40 MB，也就是 `-debug-full` 那个资产）。
