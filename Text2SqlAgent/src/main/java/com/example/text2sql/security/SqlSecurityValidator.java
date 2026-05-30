package com.example.text2sql.security;

import com.example.text2sql.config.SecurityConfig;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * SQL 安全校验器
 *
 * 多层次安全校验：
 * 1. 关键字黑名单检查
 * 2. SQL 注入模式匹配
 * 3. JSqlParser AST 语法树分析
 * 4. 结构安全检查
 */
@Component
public class SqlSecurityValidator {

    private static final Logger logger = LoggerFactory.getLogger(SqlSecurityValidator.class);
    private final SecurityConfig securityConfig;

    public SqlSecurityValidator(SecurityConfig securityConfig) {
        this.securityConfig = securityConfig;
    }

    public ValidationResult validate(String sql) {
        List<String> violations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (sql == null || sql.trim().isEmpty()) {
            return ValidationResult.fail("SQL 为空");
        }

        String trimmedSql = sql.trim();
        String upperSql = trimmedSql.toUpperCase();

        // === 第一层：关键字黑名单检查 ===
        for (String keyword : securityConfig.getSqlBlacklist()) {
            // 用词边界匹配，避免误判（例如 column_name 包含了 INSERT 字符串）
            if (upperSql.matches(".*\\b" + Pattern.quote(keyword) + "\\b.*")) {
                violations.add("禁止的关键字: " + keyword);
            }
        }

        // === 第二层：危险模式匹配 ===
        for (String pattern : securityConfig.getForbiddenPatterns()) {
            if (upperSql.matches(pattern)) {
                violations.add("匹配到危险模式: " + pattern);
            }
        }

        // === 第三层：JSqlParser AST 分析 ===
        try {
            Statement statement = CCJSqlParserUtil.parse(trimmedSql);

            // 必须是 SELECT 语句
            if (!(statement instanceof Select)) {
                violations.add("仅允许 SELECT 语句，当前类型: " + statement.getClass().getSimpleName());
            } else {
                Select selectStmt = (Select) statement;

                // 检查是否是 SELECT ... INTO (可能写文件)
                String upperSelectBody = selectStmt.toString().toUpperCase();
                if (upperSelectBody.contains("INTO OUTFILE") || upperSelectBody.contains("INTO DUMPFILE")) {
                    violations.add("禁止 SELECT ... INTO OUTFILE/DUMPFILE");
                }
            }
        } catch (Exception e) {
            // JSqlParser 解析失败可能不是安全问题，可能是语法问题
            warnings.add("SQL 语法解析警告: " + e.getMessage());
            logger.warn("JSqlParser 解析 SQL 失败: {}", e.getMessage());
        }

        // === 第四层：结构安全检查 ===
        // 检查 SQL 长度
        if (trimmedSql.length() > securityConfig.getMaxSqlLength()) {
            violations.add("SQL 超出最大长度限制: " + securityConfig.getMaxSqlLength());
        }

        // 检查分号数量（防多语句注入）
        int semicolonCount = trimmedSql.length() - trimmedSql.replace(";", "").length();
        if (semicolonCount > 0 && !trimmedSql.trim().endsWith(";")) {
            violations.add("SQL 包含分号但不在末尾，疑似多语句: 分号数量=" + semicolonCount);
        }
        if (semicolonCount > 1) {
            violations.add("SQL 包含多个分号，疑似多语句注入: 分号数量=" + semicolonCount);
        }

        if (!violations.isEmpty()) {
            return ValidationResult.fail(String.join("; ", violations));
        }

        if (!warnings.isEmpty()) {
            return ValidationResult.warn(String.join("; ", warnings));
        }

        return ValidationResult.pass();
    }

    public record ValidationResult(
            boolean approved,
            String message,
            List<String> violations,
            List<String> warnings
    ) {
        public static ValidationResult pass() {
            return new ValidationResult(true, "安全检查通过", List.of(), List.of());
        }

        public static ValidationResult fail(String reason) {
            return new ValidationResult(false, reason, List.of(reason), List.of());
        }

        public static ValidationResult warn(String message) {
            return new ValidationResult(true, message, List.of(), List.of(message));
        }
    }
}
