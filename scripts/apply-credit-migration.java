import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;

/** MySQL 手工建表入口。默认只读检查，显式 --apply 才执行 V30 和 V31 的建表 SQL。 */
class ApplyCreditMigration {
    private static final Path MIGRATIONS = Path.of("Qingfeng_AI_Music/qf-infrastructure/src/main/resources/db/migration");
    private static final List<String> SCRIPTS = List.of("V30__credit_consumption.sql", "V31__rewrite_charges.sql");

    private record Table(String name, String columns, String indexes, String creatorColumn) {}

    private static final List<Table> TABLES = List.of(
        new Table("credit_consumption",
            "id:varchar(36):NO,owner_id:varchar(36):NO,consumption_type:varchar(32):NO,work_id:varchar(36):NO,request_key:varchar(36):NO,amount:bigint:NO,task_status:varchar(16):NO,refunded:int:NO,created_at:timestamp(6):NO,updated_at:timestamp(6):NO",
            "idx_credit_consumption_owner:1:owner_id,created_at;idx_credit_consumption_status:1:task_status;PRIMARY:0:id;uq_credit_consumption:0:consumption_type,work_id,request_key", "owner_id"),
        new Table("daily_quota",
            "creator_id:varchar(36):NO,bucket:varchar(32):NO,usage_day:date:NO,used_count:int:NO,updated_at:timestamp(6):NO",
            "idx_daily_quota_day:1:usage_day;PRIMARY:0:creator_id,bucket,usage_day", "creator_id"),
        new Table("rewrite_consumption",
            "id:varchar(36):NO,creator_id:varchar(36):NO,request_key:varchar(36):NO,amount:bigint:NO,charged:int:NO,status:varchar(16):NO,result_text:text:YES,refunded:int:NO,created_at:timestamp(6):NO,updated_at:timestamp(6):NO",
            "idx_rewrite_consumption_created:1:created_at;idx_rewrite_consumption_reserved:1:status,creator_id;PRIMARY:0:id;uq_rewrite_consumption:0:creator_id,request_key", "creator_id")
    );

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2 || args.length == 2 && !args[1].equals("--apply")) {
            throw new IllegalArgumentException("用法：java -cp <mysql-connector-j.jar> scripts/apply-credit-migration.java <私有配置路径> [--apply]");
        }
        boolean apply = args.length == 2;
        var settings = new Properties();
        try (var input = Files.newInputStream(Path.of(args[0]))) {
            settings.load(input);
        }
        try (var connection = DriverManager.getConnection(settings.getProperty("spring.datasource.url"),
                settings.getProperty("spring.datasource.username"), settings.getProperty("spring.datasource.password", ""))) {
            connection.setReadOnly(!apply);
            boolean missing = inspect(connection);
            if (apply && missing) {
                for (String file : SCRIPTS) {
                    String sql = Files.readString(MIGRATIONS.resolve(file)).replaceAll("(?m)^\\s*--[^\\r\\n]*$", "");
                    for (String command : sql.split(";")) {
                        if (command.isBlank()) continue;
                        try (var statement = connection.createStatement()) {
                            statement.execute(command);
                        }
                    }
                }
                if (inspect(connection)) throw new IllegalStateException("执行后仍有积分表缺失");
            }
            System.out.println(apply ? "积分表结构已核对。此入口不登记 Flyway 历史。" : "只读检查完成。");
        } catch (SQLException error) {
            // MySQL DDL 会隐式提交，失败后应重新核对实际结构再决定是否重试。
            System.err.println("数据库操作失败，SQLState=" + error.getSQLState() + "，错误码=" + error.getErrorCode());
            System.exit(2);
        }
    }

    private static boolean inspect(Connection connection) throws SQLException {
        boolean missing = false;
        for (Table table : TABLES) {
            String columns = query(connection, """
                SELECT GROUP_CONCAT(CONCAT(column_name,':',column_type,':',is_nullable) ORDER BY ordinal_position)
                  FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=?
                """, table.name());
            if (columns == null) {
                missing = true;
                System.out.println(table.name() + "：缺失");
                continue;
            }
            String indexes = query(connection, """
                SELECT GROUP_CONCAT(CONCAT(index_name,':',non_unique,':',cols) ORDER BY index_name SEPARATOR ';')
                  FROM (SELECT index_name,non_unique,GROUP_CONCAT(column_name ORDER BY seq_in_index) AS cols
                          FROM information_schema.statistics
                         WHERE table_schema=DATABASE() AND table_name=?
                         GROUP BY index_name,non_unique) AS index_shape
                """, table.name());
            String creator = query(connection, """
                SELECT GROUP_CONCAT(column_name ORDER BY column_name)
                  FROM information_schema.key_column_usage WHERE table_schema=DATABASE() AND table_name=?
                   AND referenced_table_schema=DATABASE() AND referenced_table_name='creator' AND referenced_column_name='id'
                """, table.name());
            if (!table.columns().equals(columns) || !table.indexes().equals(indexes) || !table.creatorColumn().equals(creator)) {
                throw new IllegalStateException(table.name() + " 的结构与预期不一致，请先核对结构再执行迁移");
            }
            System.out.println(table.name() + "：结构匹配");
        }
        return missing;
    }

    private static String query(Connection connection, String sql, String table) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (var result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }
}
