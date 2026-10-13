-- 样例验收查询
-- 此文件验证确定性SQL结果；真实模型生成正确性需另行显式评估。
USE sqlfinder_sakila;

-- rental-range: 租赁数据的起止时间、总次数和已归还次数分别是多少？
-- 按租赁日期统计，归还次数使用COUNT(return_date)。
SELECT MIN(rental_date) AS first_rental, MAX(rental_date) AS last_rental,
       COUNT(*) AS rental_count, COUNT(return_date) AS returned_count FROM rental;

-- payment-range: 付款数据的起止时间、总笔数和付款总额分别是多少？
-- 已收金额按payment.amount求和，不根据租赁单价推算。
SELECT MIN(payment_date) AS first_payment, MAX(payment_date) AS last_payment,
       COUNT(*) AS payment_count, SUM(amount) AS total_revenue FROM payment;

-- monthly-revenue: 按年月统计全部付款金额，按时间升序排列。
-- 按付款日期分组，仅显示有付款的月份；不补没有数据的月份。
SELECT DATE_FORMAT(payment_date, '%Y-%m') AS month, SUM(amount) AS revenue
FROM payment GROUP BY DATE_FORMAT(payment_date, '%Y-%m') ORDER BY month;

-- top-categories: 租借次数最多的五个影片类别是什么？返回类别名称和租借次数。
-- 按租赁记录计数，类别并列时按类别名称升序。
SELECT c.name AS category, COUNT(r.rental_id) AS rental_count
FROM category c JOIN film_category fc ON fc.category_id = c.category_id
JOIN inventory i ON i.film_id = fc.film_id
JOIN rental r ON r.inventory_id = i.inventory_id
GROUP BY c.category_id, c.name ORDER BY rental_count DESC, category ASC LIMIT 5;

-- never-rented: 哪些影片从未被租借过，包括没有库存的影片？按影片编号升序。
-- 包含没有库存的电影，不能将无库存电影漏掉。
SELECT f.film_id, f.title FROM film f
LEFT JOIN inventory i ON i.film_id = f.film_id
LEFT JOIN rental r ON r.inventory_id = i.inventory_id
GROUP BY f.film_id, f.title HAVING COUNT(r.rental_id) = 0 ORDER BY f.film_id;

-- country-revenue: 按客户国家统计去重客户数量和全部付款金额，列出收入最高的十个国家。
-- 客户数必须去重，付款不得重复求和；收入并列按国家名升序。
SELECT co.country, COUNT(DISTINCT cu.customer_id) AS customer_count,
       COALESCE(SUM(p.amount), 0) AS revenue
FROM country co JOIN city ci ON ci.country_id = co.country_id
JOIN address a ON a.city_id = ci.city_id
JOIN customer cu ON cu.address_id = a.address_id
LEFT JOIN payment p ON p.customer_id = cu.customer_id
GROUP BY co.country_id, co.country ORDER BY revenue DESC, co.country ASC LIMIT 10;

-- average-rental-days: 各影片类别已归还租赁的平均租期是多少？按日历天数计算并保留两位小数。
-- 排除NULL return_date；DATEDIFF是日期差，不是精确24小时数。
SELECT c.name AS category, ROUND(AVG(DATEDIFF(r.return_date, r.rental_date)), 2) AS average_days
FROM category c JOIN film_category fc ON fc.category_id = c.category_id
JOIN inventory i ON i.film_id = fc.film_id
JOIN rental r ON r.inventory_id = i.inventory_id
WHERE r.return_date IS NOT NULL
GROUP BY c.category_id, c.name ORDER BY average_days DESC, category ASC;

-- overview-counts: 分别统计电影总数、客户总数、租赁总次数。
-- 独立子查询统计三张表，不能三表直接笛卡尔关联计数。
SELECT (SELECT COUNT(*) FROM film) AS film_count, (SELECT COUNT(*) FROM customer) AS customer_count, (SELECT COUNT(*) FROM rental) AS rental_count;

-- unreturned-count: 尚未归还的租赁有多少条？
-- 尚未归还是return_date IS NULL；不能用等于NULL。
SELECT COUNT(*) AS unreturned_count FROM rental WHERE return_date IS NULL;

-- no-inventory-count: 没有任何库存副本的影片有多少部？
-- 电影维度计数，不是库存维度；NOT EXISTS包含零库存影片。
SELECT COUNT(*) AS film_count FROM film f WHERE NOT EXISTS (SELECT 1 FROM inventory i WHERE i.film_id = f.film_id);

-- stocked-never-rented: 有库存但从未被租借的影片有多少部？
-- 必须有库存，且所有库存副本均无租赁；与零库存影片区别。
SELECT COUNT(*) AS film_count FROM film f WHERE EXISTS (SELECT 1 FROM inventory i WHERE i.film_id = f.film_id) AND NOT EXISTS (SELECT 1 FROM inventory i JOIN rental r ON r.inventory_id = i.inventory_id WHERE i.film_id = f.film_id);

-- top-customer-value: 累计付款金额最高的十位客户是谁？返回客户编号、姓名、付款总额和租赁次数。
-- 付款与租赁分别聚合后关联，避免两张明细表交叉相乘；同额按客户编号升序。
SELECT c.customer_id, c.first_name, c.last_name, p.revenue, COALESCE(r.rental_count, 0) AS rental_count
FROM customer c JOIN (SELECT customer_id, SUM(amount) AS revenue FROM payment GROUP BY customer_id) p ON p.customer_id = c.customer_id
LEFT JOIN (SELECT customer_id, COUNT(*) AS rental_count FROM rental GROUP BY customer_id) r ON r.customer_id = c.customer_id
ORDER BY p.revenue DESC, c.customer_id ASC LIMIT 10;

-- monthly-store-revenue: 按收款员工所属门店统计每月付款金额，按月份和门店编号升序。
-- 门店归属按payment.staff_id -> staff.store_id，不按客户或库存归属。
SELECT DATE_FORMAT(p.payment_date, '%Y-%m') AS month, s.store_id, SUM(p.amount) AS revenue
FROM payment p JOIN staff s ON s.staff_id = p.staff_id
GROUP BY DATE_FORMAT(p.payment_date, '%Y-%m'), s.store_id ORDER BY month, s.store_id;

-- top-films-inventory: 被租借次数最多的十部影片是什么？同时返回影片编号、名称、租赁次数和库存副本数量。
-- 库存副本使用COUNT(DISTINCT inventory_id)避免被租赁连接倍增；并列按影片编号升序。
SELECT f.film_id, f.title, COUNT(r.rental_id) AS rental_count, COUNT(DISTINCT i.inventory_id) AS inventory_count
FROM film f JOIN inventory i ON i.film_id = f.film_id LEFT JOIN rental r ON r.inventory_id = i.inventory_id
GROUP BY f.film_id, f.title ORDER BY rental_count DESC, f.film_id ASC LIMIT 10;

