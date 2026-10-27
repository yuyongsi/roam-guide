# JDK 17 / 21 兼容性验证（2026-10-27）

在 Windows 本机的隔离源码副本上，分别使用以下完整 JDK 构建并运行了当前版本：

| 环境 | 实际版本 | 结果 |
| --- | --- | --- |
| Eclipse Temurin 17 | 17.0.20.1+1，Windows x64 | 通过 |
| JetBrains Runtime 21 | JBR 21.0.10+1-1163.108-jcef，Windows x64 | 通过 |

Temurin 17 便携包从 [Adoptium 官方 API](https://adoptium.net/installation/ci-scripts/) 指向的发布地址下载，并与官方 API 提供的 SHA-256 核对一致：`e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0`。JBR 21 使用本机 IDE 自带的完整 JDK。测试只为当前进程设置 Java 路径，没有修改系统环境变量；JDK 和 Maven 缓存不放入源码交付。

两次测试各自在不含 `data/` 和 `target/` 的源码副本中，使用根目录 `run.ps1 -NoBrowser -Offline -Rebuild` 和已准备的 Maven 依赖，从源码完成编译并启动，端口分别为 8836、8837。检查了新数据自动初始化、中文景点和攻略全文搜索、traveler 登录、创建行程、保存预算、读取版本历史、关闭并重启后登录和行程/预算/版本保留，以及 SQLite 完整性和外键检查。测试使用进程终止模拟中断重启；它不代替 Ctrl+C 正常停站检查。两个副本的 `src/`、`sql/` 共 168 个文件及 `pom.xml`、`run.ps1` 的哈希均与验证时项目源码一致。两个 JAR 的哈希不同，不能据此认定构建内容不同或一致。

另外使用真实运行后的独立数据目录，上传图片，停站后运行备份脚本、校验备份清单，再从备份副本启动。管理员上传图片、traveler 登录、测试行程、30 个景点和恢复后的图片读取均通过；图片的 SHA-256 与备份前一致。恢复步骤和适用边界见[备份说明](backup.md)。

本轮验证针对上述两个实际版本与列出的场景，不代表所有厂商、版本或目标电脑环境均已测试；首次无缓存构建仍需网络。打印预览的功能验证另见[打印说明](print-itinerary.md)；原生打印窗口保存 PDF 及多分辨率窗口检查受本轮安全工具限制，未实测，不能将其视为已验证。

项目目录外的工作区测试资料位于 `work/jdk-check-final-17/`、`work/jdk-check-final-21/` 和 `work/jdk-check-21/restore-result-final.json`；各自含结果 JSON 和启动日志，测试副本与数据不属于交付源码。
