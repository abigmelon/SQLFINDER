-- 样例只读授权
-- 管理员执行；sqlfinder_reader 需事先存在；保留其原有授权。
GRANT SELECT ON sqlfinder_sakila.actor TO 'sqlfinder_reader'@'localhost';
GRANT SELECT ON sqlfinder_sakila.address TO 'sqlfinder_reader'@'localhost';
GRANT SELECT ON sqlfinder_sakila.category TO 'sqlfinder_reader'@'localhost';
GRANT SELECT ON sqlfinder_sakila.city TO 'sqlfinder_reader'@'localhost';
GRANT SELECT ON sqlfinder_sakila.country TO 'sqlfinder_reader'@'localhost';
GRANT SELECT ON sqlfinder_sakila.customer TO 'sqlfinder_reader'@'localhost';
GRANT SELECT ON sqlfinder_sakila.film TO 'sqlfinder_reader'@'localhost';
GRANT SELECT ON sqlfinder_sakila.film_actor TO 'sqlfinder_reader'@'localhost';
GRANT SELECT ON sqlfinder_sakila.film_category TO 'sqlfinder_reader'@'localhost';
GRANT SELECT ON sqlfinder_sakila.film_text TO 'sqlfinder_reader'@'localhost';
GRANT SELECT ON sqlfinder_sakila.inventory TO 'sqlfinder_reader'@'localhost';
GRANT SELECT ON sqlfinder_sakila.language TO 'sqlfinder_reader'@'localhost';
GRANT SELECT ON sqlfinder_sakila.payment TO 'sqlfinder_reader'@'localhost';
GRANT SELECT ON sqlfinder_sakila.rental TO 'sqlfinder_reader'@'localhost';
GRANT SELECT ON sqlfinder_sakila.staff TO 'sqlfinder_reader'@'localhost';
GRANT SELECT ON sqlfinder_sakila.store TO 'sqlfinder_reader'@'localhost';
