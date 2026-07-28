# 墨白 AI Android APP

这是可以单独上传到 GitHub 的手机端完整项目，不需要上传后端主项目。

## 上传

1. 在 GitHub 新建一个空仓库。
2. 将 `MBai_App_Package` 文件夹里面的全部内容上传到仓库根目录。
3. 必须包含隐藏目录 `.github`，否则不会自动打包。
4. 上传完成后打开仓库的 `Actions` 页面。
5. 选择 `Build Android APK`，等待绿色成功标记。
6. 在本次运行页面底部的 `Artifacts` 下载 `MBai-debug-apk`。

Android 工程位于 `android_app_project`，GitHub 会自动使用 JDK 17 和 Android SDK 36
执行测试并生成：

`android_app_project/app/build/outputs/apk/debug/app-debug.apk`

APP 版本：`2.0.5 (205)`。

请不要上传 `.gradle`、`build`、`local.properties`、签名文件或旧 APK；这些内容已经通过
`.gitignore` 排除。
