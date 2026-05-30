-- =====================================================
-- Text2SQL Agent 演示数据库初始化脚本
-- =====================================================

-- 商品分类表
CREATE TABLE IF NOT EXISTS categories (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    parent_id INT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 商品表
CREATE TABLE IF NOT EXISTS products (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    price DECIMAL(10, 2) NOT NULL,
    stock INT DEFAULT 0,
    category_id INT,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 用户表
CREATE TABLE IF NOT EXISTS users (
    id INT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(100),
    phone VARCHAR(20),
    city VARCHAR(50),
    registration_date DATE,
    vip_level VARCHAR(20) DEFAULT 'NORMAL',
    total_spent DECIMAL(12, 2) DEFAULT 0.00,
    is_active BOOLEAN DEFAULT TRUE
);

-- 订单表
CREATE TABLE IF NOT EXISTS orders (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    order_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    total_amount DECIMAL(12, 2) NOT NULL,
    status VARCHAR(20) DEFAULT 'PENDING',
    payment_method VARCHAR(30),
    shipping_address VARCHAR(300)
);

-- 订单明细表
CREATE TABLE IF NOT EXISTS order_items (
    id INT AUTO_INCREMENT PRIMARY KEY,
    order_id INT NOT NULL,
    product_id INT NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    unit_price DECIMAL(10, 2) NOT NULL,
    subtotal DECIMAL(12, 2) NOT NULL
);

-- 商品-分类关联表（多对多）
CREATE TABLE IF NOT EXISTS product_categories (
    product_id INT NOT NULL,
    category_id INT NOT NULL,
    PRIMARY KEY (product_id, category_id)
);

-- =====================================================
-- 插入演示数据
-- =====================================================

-- 分类
INSERT INTO categories (id, name, description) VALUES
(1, '电子产品', '手机、电脑、平板等电子设备'),
(2, '服装鞋帽', '男女服装、鞋子、帽子等'),
(3, '食品饮料', '零食、饮料、生鲜等'),
(4, '图书音像', '书籍、音乐、影视等'),
(5, '家居用品', '家具、厨具、日用品等');

-- 商品
INSERT INTO products (id, name, price, stock, category_id, is_active) VALUES
(1, 'iPhone 15 Pro', 8999.00, 150, 1, TRUE),
(2, 'MacBook Air M3', 10999.00, 80, 1, TRUE),
(3, 'AirPods Pro 2', 1899.00, 300, 1, TRUE),
(4, '男士商务衬衫', 299.00, 500, 2, TRUE),
(5, '女士连衣裙', 459.00, 200, 2, TRUE),
(6, '有机坚果礼盒', 128.00, 1000, 3, TRUE),
(7, 'Java编程思想', 99.00, 50, 4, TRUE),
(8, '智能扫地机器人', 2499.00, 60, 5, TRUE),
(9, '机械键盘 K8', 599.00, 200, 1, TRUE),
(10, '运动跑鞋', 799.00, 150, 2, TRUE);

-- 用户
INSERT INTO users (id, username, email, phone, city, registration_date, vip_level, total_spent) VALUES
(1, 'alice_wang', 'alice@example.com', '13800001111', '北京', '2024-01-15', 'GOLD', 156000.00),
(2, 'bob_li', 'bob@example.com', '13800002222', '上海', '2024-02-20', 'PLATINUM', 289000.00),
(3, 'charlie_zhang', 'charlie@example.com', '13800003333', '深圳', '2024-03-10', 'NORMAL', 8500.00),
(4, 'diana_chen', 'diana@example.com', '13800004444', '杭州', '2024-04-05', 'GOLD', 125000.00),
(5, 'eve_liu', 'eve@example.com', '13800005555', '广州', '2024-05-18', 'NORMAL', 3200.00),
(6, 'frank_wu', 'frank@example.com', '13800006666', '北京', '2024-06-01', 'PLATINUM', 350000.00),
(7, 'grace_zhao', 'grace@example.com', '13800007777', '上海', '2024-07-12', 'GOLD', 98000.00),
(8, 'henry_sun', 'henry@example.com', '13800008888', '成都', '2024-08-23', 'NORMAL', 5200.00),
(9, 'ivy_zhou', 'ivy@example.com', '13800009999', '深圳', '2024-09-30', 'GOLD', 78000.00),
(10, 'jack_ma', 'jack@example.com', '13800000000', '杭州', '2024-10-01', 'PLATINUM', 500000.00);

-- 订单
INSERT INTO orders (id, user_id, order_date, total_amount, status, payment_method) VALUES
(1, 1, '2025-01-10 10:30:00', 10898.00, 'COMPLETED', 'WECHAT_PAY'),
(2, 2, '2025-01-12 14:20:00', 1599.00, 'COMPLETED', 'ALIPAY'),
(3, 3, '2025-01-15 09:00:00', 299.00, 'COMPLETED', 'WECHAT_PAY'),
(4, 1, '2025-01-20 16:45:00', 2499.00, 'COMPLETED', 'CREDIT_CARD'),
(5, 4, '2025-01-25 11:00:00', 10998.00, 'COMPLETED', 'ALIPAY'),
(6, 2, '2025-02-01 08:30:00', 8999.00, 'COMPLETED', 'WECHAT_PAY'),
(7, 5, '2025-02-05 13:15:00', 128.00, 'COMPLETED', 'ALIPAY'),
(8, 6, '2025-02-10 10:00:00', 18999.00, 'COMPLETED', 'CREDIT_CARD'),
(9, 7, '2025-02-15 09:30:00', 459.00, 'COMPLETED', 'WECHAT_PAY'),
(10, 2, '2025-02-20 15:45:00', 599.00, 'COMPLETED', 'ALIPAY'),
(11, 8, '2025-02-25 12:00:00', 799.00, 'COMPLETED', 'WECHAT_PAY'),
(12, 9, '2025-03-01 10:30:00', 2499.00, 'PENDING', 'ALIPAY'),
(13, 1, '2025-03-05 14:20:00', 99.00, 'COMPLETED', 'WECHAT_PAY'),
(14, 10, '2025-03-10 09:00:00', 11998.00, 'COMPLETED', 'CREDIT_CARD'),
(15, 6, '2025-03-15 16:45:00', 1899.00, 'COMPLETED', 'ALIPAY'),
(16, 4, '2025-03-20 11:00:00', 128.00, 'COMPLETED', 'WECHAT_PAY'),
(17, 3, '2025-03-25 08:30:00', 459.00, 'COMPLETED', 'ALIPAY'),
(18, 7, '2025-04-01 13:15:00', 10999.00, 'COMPLETED', 'CREDIT_CARD'),
(19, 9, '2025-04-05 10:00:00', 599.00, 'PENDING', 'WECHAT_PAY'),
(20, 10, '2025-04-10 09:30:00', 1899.00, 'COMPLETED', 'ALIPAY');

-- 订单明细
INSERT INTO order_items (order_id, product_id, quantity, unit_price, subtotal) VALUES
(1, 1, 1, 8999.00, 8999.00),
(1, 3, 1, 1899.00, 1899.00),
(2, 5, 1, 1599.00, 1599.00),
(3, 4, 1, 299.00, 299.00),
(4, 8, 1, 2499.00, 2499.00),
(5, 2, 1, 10998.00, 10998.00),
(6, 1, 1, 8999.00, 8999.00),
(7, 6, 1, 128.00, 128.00),
(8, 1, 2, 8999.00, 17998.00),
(8, 5, 1, 1001.00, 1001.00),
(9, 5, 1, 459.00, 459.00),
(10, 9, 1, 599.00, 599.00),
(11, 10, 1, 799.00, 799.00),
(12, 8, 1, 2499.00, 2499.00),
(13, 7, 1, 99.00, 99.00),
(14, 1, 1, 8999.00, 8999.00),
(14, 3, 1, 1899.00, 1899.00),
(14, 6, 2, 128.00, 256.00),
(14, 9, 1, 599.00, 599.00),
(14, 4, 1, 245.00, 245.00),
(15, 3, 1, 1899.00, 1899.00),
(16, 6, 1, 128.00, 128.00),
(17, 5, 1, 459.00, 459.00),
(18, 2, 1, 10999.00, 10999.00),
(19, 9, 1, 599.00, 599.00),
(20, 3, 1, 1899.00, 1899.00);

-- 商品-分类关联
INSERT INTO product_categories (product_id, category_id) VALUES
(1, 1), (2, 1), (3, 1), (9, 1),
(4, 2), (5, 2), (10, 2),
(6, 3),
(7, 4),
(8, 5);
