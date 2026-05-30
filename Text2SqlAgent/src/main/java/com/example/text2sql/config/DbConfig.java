package com.example.text2sql.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "text2sql.db")
public class DbConfig {

    /** JDBC连接URL */
    private String url = "jdbc:h2:mem:text2sql;DB_CLOSE_DELAY=-1";
    /** 数据库用户名 */
    private String username = "sa";
    /** 数据库密码 */
    private String password = "";
    /** 数据库schema */
    private String schema = "PUBLIC";
    /** 连接类型: jdbc */
    private String connectionType = "jdbc";
    /** 数据库方言 */
    private String dialectType = "h2";

}
