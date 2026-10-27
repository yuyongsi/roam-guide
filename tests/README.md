# 约束规划算法独立检查

只依赖 JDK 17 或更新版本，不访问网络或数据库。在项目根目录执行：

    javac --release 17 -encoding UTF-8 -d target/planner-check src/main/java/cn/edu/travel/ConstraintPlanner.java tests/verify_constraint_core.java
    java -cp target/planner-check verify_constraint_core

检查直接调用实际 ConstraintPlanner，包含固定日期、必去、时段合计、唯一站点、确定性和搜索上限分支。240 组固定随机种子的小例与穷举分配可行性对照；另检查18候选7天的有界负载。耗时是本机单次观测，不作为性能保证。


# 中文全文搜索检查

先在项目目录完成 mvn package，以下检查直接调用实际实现，依赖JDK 17+：

    javac --release 17 -encoding UTF-8 -cp target/classes -d target/search-check src/main/java/cn/edu/travel/SearchText.java tests/verify_search_text.java
    java -cp "target/search-check;target/classes" verify_search_text

Windows classpath分隔符为分号；Linux/macOS请改为冒号。共421项断言，覆盖原文映射、全角、英文词边界、组合词、标点边界、短语、Unicode补充字符与摘录。

启动网站后，使用Python 3标准库运行真实接口/数据库检查：

    python tests/verify_fulltext.py

使用公开体验管理员进行临时内容的创建与修改；finally清理本次创建的临时数据，城市临时更名也会恢复。请在本地课程体验数据库中运行，运行期间不要并行编辑内容。检查39项，并将结果写入target/fulltext-checks.json。

前端转义检查使用Node.js，无第三方依赖：

    node tests/verify_fulltext_ui.cjs


# 六项审阅修复的严格检查

启动网站后使用Python标准库执行：

    python tests/verify_audit_fixes.py

默认连接本机8818端口和项目data/guide.db。可设置ROAM_BASE与ROAM_QA_DB对独立测试副本运行。29项检查使用原始Session，不自动补充缺少的版本；创建带qa_fix前缀的账号与临时内容，finally按本次记录的编号清理。请勿在检查期间并行编辑这些临时内容。

    node tests/verify_form_safety.cjs

27项断言直接加载实际表单保护与身份接收函数，检查未保存提醒、关闭取消、离开保护、动态字段、身份隔离和匿名登录后继续操作。

既有全文搜索基线检查的VersionedSession只在没有显式版本时读取当前版本，适配升级后的请求格式；严格缺参与旧版本测试不采用该辅助。


# 城市管理严格检查

    python tests/verify_city_management.py

31项检查使用Python标准库及原始Session，覆盖目录、省市校验、并发去重、版本冲突、待完善内容隔离和展示条件。默认连接8818与项目data/guide.db；建议设置ROAM_BASE与ROAM_QA_DB指向独立测试服务及其数据库，检查期间不要并行编辑内容。只清理本次记录的临时编号，不删除已有城市；结果写入target/city-management-checks.json。详见docs/city-management.md。


# 评论时间排序检查

    python tests/verify_comment_order.py

只使用Python标准库与内存数据库，执行实际Java文件中的4条列表查询。编号与时间特意交错，并安排两个相同时间，断言按时间倒序、同刻按编号倒序；不连接或修改真实数据库。


# 备份与打印检查（2026-10-27）

    python tests/verify_backup.py
    node tests/verify_print_ui.cjs

备份检查使用独立临时文件夹和 Java 文件锁，需要完整 JDK（设置 JAVA_HOME 或加入 PATH），不使用正式数据。打印界面检查使用 Node 执行源码断言。

    $env:ROAM_BASE = 'http://127.0.0.1:8836'
    $env:ROAM_QA_DB = 'D:\print-check\data\guide.db'
    python tests/verify_print_itinerary.py

打印接口检查会写入测试数据，只能连接 883x 端口上的一次性隔离测试服务，ROAM_QA_DB 必须指向该服务的数据副本且路径包含 print-check。上述路径只是示例；不得指向正式 data，检查脚本也不能代替确认服务与数据库确实属于同一个隔离副本。

# 登录失败冷却（2026-10-27）

    javac -encoding UTF-8 -cp target/classes -d target/verify-login tests/verify_login_cooldown.java
    java -cp "target/classes;target/verify-login" cn.edu.travel.verify_login_cooldown
    node tests/verify_login_cooldown_ui.cjs

先构建项目；Java 核心检查采用可控时钟，覆盖阈值、到期、不延长、成功清理、窗口跨界、来源累计、隔离、容量和并发。Node 检查使用实际前端源码，验证计时、注册切换、来源与组合范围及请求过程中的状态判断。

    $env:ROAM_BASE = 'http://127.0.0.1:8832'
    python tests/verify_login_cooldown_http.py

HTTP 检查会创建测试账号、改密码并故意触发登录限制，真实等待 61 秒；只可在 8832 的一次性隔离数据副本运行，不得连接正式数据。检查服务的运行目录和数据库确属隔离副本。测试后重启隔离服务，清除冷却记录，再执行其他回归。
