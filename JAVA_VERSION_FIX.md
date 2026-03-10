# 🔧 Java 版本兼容性修复指南

## 问题描述

```
Incompatible Gradle JVM version
The project's Gradle version 8.4 is incompatible with the Gradle JVM version 21.
Gradle 8.4 supports Java versions between 1.8 and 20. 
Please update the selected JVM to a compatible version.
```

## 原因

- Gradle 8.4 只支持 Java 1.8 - 20
- 你的系统安装了 Java 21（或更高版本）
- Gradle 需要升级才能支持 Java 21

## ✅ 已应用的修复

### 1. Gradle 版本升级
```
gradle-wrapper.properties:
  Gradle 8.4 → 8.7
```

### 2. Gradle 属性配置
```
gradle.properties:
  org.gradle.java.home=  # 将自动设置为你的 JAVA_HOME
```

## 🚀 快速修复（2 个选项）

### 🏃 选项 A: 自动修复（推荐）

#### Windows PowerShell
```powershell
cd "c:\Users\Zeehan\Documents\VSCode\DroidMate"
Set-ExecutionPolicy -ExecutionPolicy Bypass -Scope CurrentUser
.\fix-java-version.ps1
```

#### Windows Command Prompt
```batch
cd c:\Users\Zeehan\Documents\VSCode\DroidMate
fix-java-version.bat
```

### 🔧 选项 B: 手动修复

#### Step 1: 验证 Java 版本
```powershell
java -version
# 应该显示 Java 21 或更高版本
```

#### Step 2: 找到 JAVA_HOME
```powershell
# Windows PowerShell
$env:JAVA_HOME

# 输出应该类似:
# C:\Program Files\Java\jdk-21
```

#### Step 3: 编辑 gradle.properties
在文件末尾添加：
```properties
org.gradle.java.home=C:\Program Files\Java\jdk-21
```

#### Step 4: 清理并重新构建
```bash
gradlew.bat clean build
```

## 📊 Gradle 和 Java 兼容性表

| Gradle 版本 | 支持的 Java 版本 | 发布日期 |
|-----------|-------------|---------|
| 7.0       | 1.8 - 16    | 2021-04 |
| 7.6       | 1.8 - 18    | 2023-01 |
| 8.0       | 1.8 - 19    | 2023-02 |
| 8.4       | 1.8 - 20    | 2023-11 |
| **8.7**   | **1.8 - 21+** | **2024-04** |
| 8.10      | 1.8 - 22+   | 2024-10 |

✅ **我们使用的是 Gradle 8.7，完全支持 Java 21**

## ✓ 验证修复

### 检查 Gradle 版本
```bash
gradlew.bat --version
# 应该显示: Gradle 8.7
```

### 检查构建成功
```bash
gradlew.bat clean build
# 应该显示: BUILD SUCCESSFUL in XXs
```

## 🔍 深度诊断

如果仍有问题，运行以下诊断命令：

### 1. 检查 Java 设置
```powershell
# 显示所有 Java 相关的环境变量
Get-ChildItem "Env:*JAVA*" | Format-Table
```

### 2. 检查 Gradle 配置
```bash
gradlew.bat -v
```

### 3. 显示完整诊断信息
```bash
gradlew.bat build --debug 2>&1 > build-debug.log
# 查看 build-debug.log 了解详细信息
```

### 4. 验证 Gradle 包装器
```powershell
# 检查 gradle-wrapper.jar
Get-ChildItem "gradle\wrapper\gradle-wrapper.jar"

# 检查 gradle-wrapper.properties
Get-Content "gradle\wrapper\gradle-wrapper.properties"
```

## 🗑️ 完全清理（如果以上方案失败）

```powershell
# 1. 删除用户级 Gradle 缓存
Remove-Item -Recurse -Force "$env:USERPROFILE\.gradle" -ErrorAction SilentlyContinue

# 2. 删除项目缓存
cd "c:\Users\Zeehan\Documents\VSCode\DroidMate"
Remove-Item -Recurse -Force ".gradle", "build", "app\build" -ErrorAction SilentlyContinue

# 3. 删除 Android Studio 缓存（可选）
Remove-Item -Recurse -Force "$env:USERPROFILE\.android" -ErrorAction SilentlyContinue

# 4. 重新构建
.\gradlew.bat clean build --refresh-dependencies
```

## 🎯 常见问题

### Q: 为什么不升级 Java 版本而是升级 Gradle？

A: 因为 Java 21 是较新版本，有更好的性能和新特性。升级 Gradle 是更简单的解决方案。

### Q: 可以使用 Java 20 吗？

A: 可以。如果你想保持原来的 Gradle 8.4，可以：
```powershell
# Windows
$env:JAVA_HOME = "C:\Program Files\Java\jdk-20"
.\gradlew.bat clean build
```

### Q: 如何在项目中使用特定 Java 版本？

A: 在 `gradle.properties` 中设置：
```properties
# 强制使用特定 Java 版本
org.gradle.java.home=C:\Program Files\Java\jdk-21
```

### Q: 可以同时有多个 Java 版本吗？

A: 可以。可以在 `gradle.properties` 中指定特定版本，或设置 `JAVA_HOME` 环境变量。

### Q: Gradle 下载需要多长时间？

A: 首次下载 Gradle 8.7 约 10-30 秒，取决于网络速度。后续使用会缓存在本地。

### Q: 如何使用 Gradle 6.x？

A: 如果你需要使用旧版本 Gradle，编辑 `gradle\wrapper\gradle-wrapper.properties`：
```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-6.9.3-bin.zip
```

但不推荐，因为 Gradle 8.7 有更好的性能和新功能。

## 📚 相关资源

- [Gradle 官方文档](https://docs.gradle.org/)
- [Gradle Java 版本支持](https://docs.gradle.org/8.7/userguide/compatibility.html)
- [Java 21 发布信息](https://www.oracle.com/java/technologies/javase/21-relnotes.html)

## 🔄 完整修复流程图

```
发现 Java 21 不兼容
       ↓
升级 Gradle 8.4 → 8.7
       ↓
设置 org.gradle.java.home
       ↓
清理缓存
       ↓
重新构建
       ↓
✓ 成功
```

## ✨ 预期结果

修复完成后：

```
$ gradle -v
------------------------------------------------------------
Gradle 8.7
------------------------------------------------------------
...
JVM version 21.0.x by Oracle Corporation
```

和：

```
$ gradle build
BUILD SUCCESSFUL in 45s
```

## 📋 修复检查清单

- [ ] Java 版本已验证（java -version）
- [ ] JAVA_HOME 已设置或自动检测
- [ ] gradle.properties 已更新
- [ ] gradle-wrapper.properties 指向 Gradle 8.7
- [ ] 本地缓存已清理
- [ ] 构建成功（BUILD SUCCESSFUL）

## 🆘 如果仍然失败

1. **清理所有缓存**（见上面的"完全清理"部分）
2. **重启 IDE**（Android Studio 或 VS Code）
3. **检查防火墙**（Gradle 需要下载依赖）
4. **尝试离线模式**（如果不需要下载新依赖）

```bash
gradlew.bat --offline build
```

5. **在线求助**：提供 `gradlew.bat build --stacktrace` 的完整输出

## ✅ 修复状态

**应用的修复:**
- ✅ Gradle 版本: 8.4 → 8.7
- ✅ Java 兼容性: 支持 Java 21
- ✅ 自动化修复脚本: fix-java-version.ps1 和 fix-java-version.bat
- ✅ gradle.properties: 配置 org.gradle.java.home

**下一步:**
1. 运行 `fix-java-version.ps1` 或 `fix-java-version.bat`
2. 或手动编辑 gradle.properties 设置 JAVA_HOME
3. 运行 `gradlew.bat clean build`

---

**预期修复时间**: < 5 分钟  
**难度级别**: 简单 ⭐☆☆☆☆  
**最后更新**: 2026-03-08
