// 用 JDBC 直接执行积分相关迁移，不需要安装 mysql 客户端，也不启动应用。
//
// 用法：
//   java -cp "<mysql-connector-j.jar>" scripts/apply-credit-migration.java <私有配置路径> [迁移目录]
//
// 私有配置需包含 spring.datasource.url / username / password（与主库一致）。
// 脚本不会打印任何凭据；执行前会检查目标表是否已存在，避免重复建表。
// 失败时只回滚已执行的语句，并打印出错的语句原文。
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.*;

public class ApplyCreditMigration {
    /** 按分号切分语句，去掉以 -- 开头的注释行；本项目迁移里没有字符串内含分号的情况。 */
    static List<String> splitStatements(String sql) {
        var out = new ArrayList<String>();
        var sb = new StringBuilder();
        for (String line : sql.split("\r?\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("--") || trimmed.isEmpty()) continue;
            sb.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                String stmt = sb.toString().strip();
                out.add(stmt.substring(0, stmt.length() - 1).strip());
                sb.setLength(0);
            }
        }
        String tail = sb.toString().strip();
        if (!tail.isEmpty()) out.add(tail);
        return out;
    }

    static boolean tableExists(Connection conn, String table) throws SQLException {
        try (var ps = conn.prepareStatement(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=SCHEMA() AND table_name=?")) {
            ps.setString(1, table);
            try (var rs = ps.executeQuery()) { return rs.next() && rs.getInt(1) > 0; }
        }
    }

    static long count(Connection conn, String sql) throws SQLException {
        try (var st = conn.createStatement(); var rs = st.executeQuery(sql)) { return rs.next() ? rs.getLong(1) : -1; }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("用法：java -cp <mysql-connector-j.jar> ApplyCreditMigration <私有配置路径> [迁移目录]"); System.exit(2); }
        Path properties = Paths.get(args[0]);
        Path migrationDir = Paths.get(args.length > 1 ? args[1] : "Qingfeng_AI_Music/qf-infrastructure/src/main/resources/db/migration");
        var props = new Properties();
        try (var in = Files.newInputStream(properties)) { props.load(in); }

        String url = props.getProperty("spring.datasource.url");
        String user = props.getProperty("spring.datasource.username");
        String password = props.getProperty("spring.datasource.password");
        if (url == null || user == null) { System.err.println("私有配置缺少 spring.datasource.url / username"); System.exit(2); }
        // 只显示主机与库名，绝不显示口令。
        String target = url.replaceAll("(?i)(password|user)=[^&;]*", "$1=***");
        System.out.println("目标库：" + target);

        var applied = new ArrayList<String>();
        // 每个迁移文件对应的表：只要其中任意一张已存在就判定为已执行过，不再重复建表。
        var tablesOf = Map.of(
            "V27__credit_consumption.sql", List.of("credit_consumption"),
            "V28__rewrite_charges.sql", List.of("daily_quota", "rewrite_consumption"));
        try (var conn = DriverManager.getConnection(url, user, password == null ? "" : password)) {
            conn.setAutoCommit(false);
            for (String name : List.of("V27__credit_consumption.sql", "V28__rewrite_charges.sql")) {
                Path file = migrationDir.resolve(name);
                if (!Files.exists(file)) { System.err.println("找不到迁移文件：" + file); System.exit(2); }
                var existing = tablesOf.get(name).stream().filter(t -> { try { return tableExists(conn, t); } catch (SQLException e) { return false; } }).toList();
                if (!existing.isEmpty()) {
                    System.out.println("\n== 跳过 " + name + "（表 " + String.join(", ", existing) + " 已存在，视为已执行）==");
                    applied.add(name + "（已存在，跳过）");
                    continue;
                }
                String sql = Files.readString(file);
                var statements = splitStatements(sql);
                System.out.println("\n== 执行 " + name + "（" + statements.size() + " 条语句）==");
                for (String stmt : statements) {
                    try (var st = conn.createStatement()) { st.execute(stmt); }
                    catch (SQLException e) {
                        conn.rollback();
                        System.err.println("执行失败，已回滚：" + e.getMessage());
                        System.err.println("出错语句：" + stmt);
                        System.exit(1);
                    }
                    System.out.println("  ✓ " + stmt.lines().findFirst().orElse("").substring(0, Math.min(72, stmt.lines().findFirst().orElse("").length())));
                }
                conn.commit();
                System.out.println("  已提交 " + name);
                applied.add(name);
            }

            System.out.println("\n== 验证 ==");
            for (String t : List.of("credit_consumption", "daily_quota", "rewrite_consumption"))
                System.out.println("  表 " + t + " 存在：" + tableExists(conn, t));
            System.out.println("  credit_ledger 行数（应与执行前一致）：" + count(conn, "SELECT COUNT(*) FROM credit_ledger"));
            System.out.println("  改写流水行数（应为 0）：" + count(conn, "SELECT COUNT(*) FROM rewrite_consumption"));
            System.out.println("\n迁移完成：" + String.join(", ", applied));
        }
    }
}
