package com.example.text2sql.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "text2sql.security")
public class SecurityConfig {

    /** 是否启用SQL安全校验 */
    private boolean sqlValidationEnabled = true;

    /** 最大返回行数 */
    private int maxResultRows = 1000;

    /** 查询超时时间(秒) */
    private int queryTimeoutSeconds = 30;

    /** SQL语句最大长度 */
    private int maxSqlLength = 4096;

    /** 强制添加LIMIT */
    private boolean forceLimit = true;

    /** 默认LIMIT值（当SQL没有LIMIT时自动添加） */
    private int defaultLimit = 100;

    /** SQL关键字黑名单 */
    private List<String> sqlBlacklist = List.of(
            "INSERT", "UPDATE", "DELETE", "DROP", "TRUNCATE",
            "ALTER", "CREATE", "REPLACE", "MERGE", "GRANT",
            "REVOKE", "EXEC", "EXECUTE", "CALL", "LOAD",
            "IMPORT", "EXPORT", "BACKUP", "RESTORE"
    );

    /** 禁止的SQL模式 */
    private List<String> forbiddenPatterns = List.of(
            ".*;\\s*DROP\\s+.*",
            ".*;\\s*DELETE\\s+.*",
            ".*;\\s*INSERT\\s+.*",
            ".*;\\s*UPDATE\\s+.*",
            ".*UNION\\s+SELECT.*",
            ".*INTO\\s+OUTFILE.*",
            ".*INTO\\s+DUMPFILE.*",
            ".*LOAD_FILE\\(.*",
            ".*SLEEP\\(.*",
            ".*BENCHMARK\\(.*",
            ".*WAITFOR\\s+DELAY.*"
    );

    /** 允许的表名白名单（为空则不限制） */
    private List<String> tableWhitelist = List.of();

    /** 允许的最大JOIN表数量 */
    private int maxJoinTables = 5;

    /** 是否只读连接 */
    private boolean readOnlyConnection = true;
}
