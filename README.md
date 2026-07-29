# 墨白 AI Android APP

这是可独立上传到 GitHub 的 Android 前端项目，不包含后端和用户数据。

## GitHub 打包

仓库需要配置以下 Actions Secrets：

- `MBAI_KEYSTORE_BASE64`
- `MBAI_KEYSTORE_PASSWORD`
- `MBAI_KEY_ALIAS`
- `MBAI_KEY_PASSWORD`

Push 或手动运行 `Build Android APK` 后，下载产物：

`MBai-production-release-apk`

正式 APK 路径：

`android_app_project/app/build/outputs/apk/production/release/app-production-release.apk`

当前版本：`2.1.0 (210)`。

Pull Request 不使用签名 Secrets，只执行测试和 Debug 构建。

不要上传 `.gradle`、`build`、`local.properties`、Release 密钥库或 APK 文件。固定 Release 密钥必须离线备份；丢失后将无法继续覆盖升级已安装的正式 APP。
