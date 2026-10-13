/* 图表数据规则测试 */
const {test}=require("node:test");
const assert=require("node:assert/strict");
const {prepare,recommend,numeric,dateValue}=require("../../main/resources/static/chart-data.js");
const result=(rows,extra={})=>({columns:["category","amount"],rows:rows.map(([category,amount])=>({category,amount})),truncated:false,
    columnMetadata:[{name:"category",kind:"TEXT"},{name:"amount",kind:"DECIMAL"}],...extra});
test("DECIMAL 以原字符串保留尾随零，坐标转换与原值分离",()=>{
    const p=prepare(result([["A","123.4500"],["B","0.000"]]),"bar","category","amount");
    assert.equal(p.ok,true);assert.equal(p.points[0].value,123.45);assert.equal(p.points[0].exact,"123.4500");
    assert.match(p.notes.join(" "),/近似/);assert.equal(p.points[1].value,0);
});
for (const value of ["9007199254740992","9223372036854775807","18446744073709551615","-9223372036854775808",9007199254740992,Infinity,NaN])
    test(`拒绝无法安全绘制的数值 ${value}`,()=>assert.equal(numeric(value,"INTEGER").ok,false));
for (const value of ["0.1234567890123456789","1000000000000000.1","0."+"0".repeat(330)+"1"])
    test(`拒绝精度丢失的小数 ${value.slice(0,30)}`,()=>assert.equal(numeric(value,"DECIMAL").ok,false));
for (const value of [true,false,"", "1.0", "1e2", "01", " 1", "<img>"])
    test(`整数列不隐式转换 ${JSON.stringify(value)}`,()=>assert.equal(numeric(value,"INTEGER").ok,false));
test("安全整数边界可以绘图",()=>{
    assert.equal(numeric("9007199254740991","INTEGER").value,9007199254740991);
    assert.equal(numeric("-9007199254740991","INTEGER").value,-9007199254740991);
});
test("旧接口仅从 JSON 数值推断度量，不把数字文本当作金额",()=>{
    assert.equal(recommend(result([["A",2],["B",4]],{columnMetadata:[]})).type,"bar");
    assert.equal(recommend(result([["A","2"],["B","4"]],{columnMetadata:[]})).type,"table");
});
test("重复分类不擅自求和，包括重复的 NULL 行",()=>{
    const p=prepare(result([["A","1"],["A",null]]),"bar","category","amount");assert.equal(p.ok,false);assert.match(p.reason,/聚合/);
});
test("柱状图 NULL 留空，负值与零保持真实",()=>{
    const p=prepare(result([["A",null],["B","-5"],["C","0"]]),"bar","category","amount");
    assert.equal(p.ok,true);assert.deepEqual(p.points.map(x=>x.value),[null,-5,0]);assert.match(p.notes.join(""),/不当作零/);
});
test("缺失维度和全 NULL 度量不能静默删行",()=>{
    assert.equal(prepare(result([[null,"5"]]),"bar","category","amount").ok,false);
    assert.equal(prepare(result([["A",null],["B",null]]),"bar","category","amount").ok,false);
});
test("空字符串类别保留明确标记",()=>assert.equal(prepare(result([["","5"]]),"bar","category","amount").points[0].label,"（空字符串）"));
for (const extra of [{truncated:true},{truncated:undefined}]) test(`饼图拒绝截断或未知完整性 ${extra.truncated}`,()=>{
    const p=prepare(result([["A","5"]],extra),"pie","category","amount");assert.equal(p.ok,false);assert.match(p.reason,/截断/);
});
for (const rows of [[["A","-1"],["B","2"]],[["A",null]],[["A","0"],["B","0"]]])
    test(`饼图拒绝无法定义占比的数据 ${JSON.stringify(rows)}`,()=>assert.equal(prepare(result(rows),"pie","category","amount").ok,false));
test("饼图说明分母范围，不声称代表整个数据库",()=>{
    const p=prepare(result([["A","9"],["B","5"],["C","0"]]),"pie","category","amount");
    assert.equal(p.ok,true);assert.match(p.notes.join(""),/本次查询返回数据.*SQL LIMIT/);
});
test("图表不自动选取前 N 行，柱形与饼图有各自上限",()=>{
    assert.equal(prepare(result(Array.from({length:101},(_,i)=>["c"+i,"1"])),"bar","category","amount").ok,false);
    assert.equal(prepare(result(Array.from({length:13},(_,i)=>["c"+i,"1"])),"pie","category","amount").ok,false);
});
test("规范日期推荐折线，排序副本不改变原始行",()=>{
    const data=result([["2026-10-12","2"],["2026-10-10","1"],["2026-10-11",null]]);
    assert.equal(recommend(data).type,"line");
    const p=prepare(data,"line","category","amount");assert.equal(p.ok,true);
    assert.deepEqual(p.points.map(x=>x.label),["2026-10-10","2026-10-11","2026-10-12"]);assert.equal(p.points[1].value,null);
    assert.equal(data.rows[0].category,"2026-10-12");assert.match(p.notes.join(""),/断点/);
});
for (const date of ["2026-02-29","2026-13-01","2026-01-01T25:00:00","2026-01-01T12:00:00.1234","2026-01-01T12:00:00+15:00","2026/01/01","2026-01-01garbage"])
    test(`拒绝无效或损失精度的时间 ${date}`,()=>assert.equal(dateValue(date),null));
test("日期解析与浏览器时区无关，保留带时区时间的绝对顺序",()=>{
    assert.equal(dateValue("2026-10-11T08:00:00+08:00"),dateValue("2026-10-11T00:00:00Z"));
    assert.equal(dateValue("2000-02-29"),Date.UTC(2000,1,29));assert.equal(dateValue("1900-02-29"),null);
    assert.equal(dateValue("2026-10"),dateValue("2026-10-01"));
});
test("折线的相同时间点必须先聚合",()=>{
    const p=prepare(result([["2026-10-11T08:00:00+08:00","1"],["2026-10-11T00:00:00Z","2"]]),"line","category","amount");
    assert.equal(p.ok,false);assert.match(p.reason,/相同.*时间点/);
});
test("折线拒绝无顺序的分类文本",()=>assert.equal(prepare(result([["A","1"],["B","2"]]),"line","category","amount").ok,false));
test("数值横轴按真实大小排序而非字符串字典顺序",()=>{
    const data=result([[10,"1"],[2,"2"]],{columnMetadata:[{name:"category",kind:"INTEGER"},{name:"amount",kind:"DECIMAL"}]});
    assert.deepEqual(prepare(data,"line","category","amount").points.map(p=>p.axis),[2,10]);
});
test("空结果或单个总数不推荐图表",()=>{
    assert.equal(recommend(result([])).type,"table");
    assert.equal(recommend({columns:["count"],rows:[{count:14}],truncated:false}).type,"table");
});
test("字段描述不匹配时拒绝绘图",()=>{
    assert.equal(prepare(result([["A","1"]],{columnMetadata:[{name:"wrong",kind:"NUMBER"}]}),"bar","category","amount").ok,false);
});
test("字段名为原型属性时也只读取自有键",()=>{
    const data=JSON.parse('{"columns":["__proto__","constructor"],"rows":[{"__proto__":"A","constructor":5}],"truncated":false}');
    assert.equal(prepare(data,"bar","__proto__","constructor").points[0].value,5);assert.equal({}.polluted,undefined);
});
test("相同显示标签不允许被图表组件合并",()=>{
    assert.equal(prepare(result([["","1"],["（空字符串）","2"]]),"bar","category","amount").ok,false);
});
