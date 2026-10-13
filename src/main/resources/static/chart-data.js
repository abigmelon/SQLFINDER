/* 图表数据规则 */
"use strict";
((root) => {
    const NUMERIC = new Set(["INTEGER", "DECIMAL", "NUMBER"]);
    const KINDS = new Set([...NUMERIC,"DATE","DATETIME","TIME","TEXT","BOOLEAN","BINARY","OTHER"]);
    const own = (row,name) => Object.hasOwn(row,name) ? row[name] : null;
    const fail = reason => ({ok:false,reason});
    function describe(data) {
        if (!Array.isArray(data?.columns) || !data.columns.every(c => typeof c === "string") || !Array.isArray(data.rows)
            || !data.rows.every(r => r && typeof r === "object" && !Array.isArray(r)) || new Set(data.columns).size !== data.columns.length)
            return {columns:[],reason:"结果字段信息不完整，请保留表格查看。"};
        const meta = data.columnMetadata;
        if (meta != null && (!Array.isArray(meta) || meta.length && (meta.length !== data.columns.length
            || !meta.every((c,i) => c?.name === data.columns[i] && KINDS.has(c.kind)))))
            return {columns:[],reason:"字段类型信息与结果不一致，请保留表格查看。"};
        const columns = data.columns.map((name,i) => {
            const values = data.rows.map(r => own(r,name)).filter(v => v != null);
            return meta?.length ? {...meta[i]} : {name,kind:values.length && values.every(v => typeof v === "number") ? "NUMBER" : "TEXT",dataType:"未提供"};
        });
        return {columns,reason:data.rows.length ? "" : "查询没有返回数据，暂无可绘制的图表。"};
    }
    function numeric(value,kind) {
        if (value == null) return {ok:true,value:null,exact:"NULL"};
        if (!NUMERIC.has(kind) || !["number","string"].includes(typeof value)) return fail("所选数值列包含非数值，请更换字段。布尔值和文本不会自动转成数字。");
        const exact=String(value);
        if (!/^-?(?:0|[1-9]\d*)(?:\.\d+)?$/.test(exact) && typeof value !== "number") return fail("所选数值列格式不正确，请保留表格查看。");
        const n=Number(value);
        if (!Number.isFinite(n) || Math.abs(n)>Number.MAX_SAFE_INTEGER || typeof value === "number" && Number.isInteger(n) && !Number.isSafeInteger(n))
            return fail("数值超出图表安全范围；表格和 CSV 保留原值，请缩小数值范围后再绘图。");
        if (kind === "INTEGER" && (!Number.isInteger(n) || typeof value === "string" && (!/^-?(?:0|[1-9]\d*)$/.test(value) || BigInt(value) !== BigInt(n))))
            return fail("整数无法安全转换为图表数值，请保留表格查看。");
        const fraction=exact.includes(".") ? exact.split(".")[1].replace(/0+$/,"") : "";
        const digits=exact.replace("-","").replace(".","").replace(/^0+/,"").replace(/0+$/,"");
        if (fraction && (digits.length>15 || n===0 && /[1-9]/.test(exact)))
            return fail("小数精度超过图表支持的 15 位有效数字；表格和 CSV 保留原值。");
        return {ok:true,value:n,exact};
    }
    // Interpret dates without a browser-local timezone; preserve original labels, never Date.parse arbitrary text.
    function dateValue(text) {
        if (typeof text !== "string") return null;
        const m=/^(\d{4})-(\d{2})(?:-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(Z|[+-]\d{2}:\d{2})?)?)?$/.exec(text);
        if (!m) return null;
        const [,ys,ms,ds,hs,mins,ss,fs,zone]=m;
        const year=+ys,month=+ms,day=ds ? +ds : 1,hour=hs ? +hs : 0,minute=mins ? +mins : 0,second=ss ? +ss : 0;
        if (month<1 || month>12 || day<1 || day>31 || hour>23 || minute>59 || second>59 || fs?.slice(3).replace(/0/g,"")) return null;
        const d=new Date(0); d.setUTCFullYear(year,month-1,day); d.setUTCHours(hour,minute,second,+(fs || "").padEnd(3,"0").slice(0,3));
        if (d.getUTCFullYear()!==year || d.getUTCMonth()!==month-1 || d.getUTCDate()!==day) return null;
        let offset=0;
        if (zone && zone!=="Z") {
            const h=+zone.slice(1,3),min=+zone.slice(4);
            if (h>14 || min>59 || h===14 && min!==0) return null;
            offset=(h*60+min)*60000*(zone[0]==="+" ? 1 : -1);
        }
        return d.getTime()-offset;
    }
    function recommend(data) {
        const info=describe(data);
        if (info.reason) return {type:"table",reason:info.reason,columns:info.columns};
        const metrics=info.columns.filter(c => NUMERIC.has(c.kind));
        const y=metrics.find(c => /count|sum|total|amount|数量|金额/i.test(c.name)) || metrics.at(-1);
        const dimensions=info.columns.filter(c => c.name !== y?.name && !["BINARY","OTHER"].includes(c.kind));
        const isDate = c => data.rows.every(r => dateValue(own(r,c.name)) !== null);
        const x=dimensions.find(isDate) || dimensions.find(c => c.kind==="TEXT") || dimensions[0];
        if (!x || !y) return {type:"table",reason:"绘图需要一个维度列和一个数值列；单个总数可直接查看表格。",columns:info.columns};
        const type=isDate(x) ? "line" : "bar";
        const prepared=prepare(data,type,x.name,y.name);
        return {type:prepared.ok ? type : "table",x:x.name,y:y.name,reason:prepared.ok ? "" : prepared.reason,columns:info.columns};
    }
    function prepare(data,type,xName,yName) {
        const info=describe(data);
        if (info.reason) return fail(info.reason);
        if (!["bar","line","pie"].includes(type)) return fail("请选择支持的图表类型。");
        const x=info.columns.find(c => c.name===xName),y=info.columns.find(c => c.name===yName);
        if (!x || !y || xName===yName || !NUMERIC.has(y.kind)) return fail("请选择不同的维度列和数值列。");
        if (["BINARY","OTHER"].includes(x.kind)) return fail("所选维度类型不支持绘图，请更换字段。");
        if (type==="pie" && data.truncated !== false) return fail("结果已截断或完整性未知，不展示饼图，避免把局部数据当成完整占比。");
        const limit={bar:100,line:1000,pie:12}[type];
        if (data.rows.length>limit) return fail(`这种图表最多展示 ${limit} 行，请先在查询中聚合或缩小范围；不会自动截取或合并数据。`);
        const seen=new Set(),axisSeen=new Set(),points=[];
        let nulls=0;
        for (const row of data.rows) {
            const dimension=own(row,xName),raw=own(row,yName);
            if (dimension == null) return fail("维度列包含 NULL，请在查询中明确处理后再绘图。");
            if (!["string","number","boolean"].includes(typeof dimension)) return fail("维度值无法用于图表，请保留表格查看。");
            const key=dimension==="" ? "（空字符串）" : String(dimension);
            if (seen.has(key)) return fail("维度值重复，请先在 SQL 中明确分组和聚合方式；图表不会自动求和。");
            seen.add(key);
            const n=numeric(raw,y.kind); if (!n.ok) return n;
            if (n.value==null) { nulls++; if (type==="pie") return fail("饼图数值包含 NULL，无法确定分母，请先处理缺失值。"); }
            if (type==="pie" && n.value<0) return fail("饼图不支持负值，请使用柱状图或修改查询口径。");
            let axis=null;
            if (type==="line") {
                if (NUMERIC.has(x.kind)) { const xn=numeric(dimension,x.kind); if (!xn.ok) return xn; axis=xn.value; }
                else axis=dateValue(dimension);
                if (axis==null) return fail("折线图横轴需要数值或规范日期（精度不超过毫秒）；分类文本可使用柱状图。");
                if (axisSeen.has(axis)) return fail("横轴存在相同数值或时间点，请先在 SQL 中明确聚合方式。");
                axisSeen.add(axis);
            }
            points.push({label:key,value:n.value,exact:n.exact,axis});
        }
        if (nulls===points.length) return fail("数值列全部为 NULL，暂无可绘制的数值。");
        if (type==="pie" && points.every(p => p.value===0)) return fail("数值合计为零，无法计算饼图占比。");
        if (type==="line") points.sort((a,b)=>a.axis-b.axis);
        const notes=["图表使用全部已返回数据，不受表格筛选或分页影响。"];
        if (data.truncated===true) notes.push("结果已截断，图表仅展示已返回的部分数据。");
        if (nulls) notes.push(type==="line" ? `${nulls} 个 NULL 保留为折线断点，不当作零。` : `${nulls} 个 NULL 不绘制柱形，不当作零。`);
        if (type==="pie") notes.push("占比以本次查询返回数据为分母；筛选条件或 SQL LIMIT 会影响范围。");
        if (type==="line" && !NUMERIC.has(x.kind)) notes.push("横轴按时间排序；无时区日期按原值，带时区的时间按 UTC 对齐。");
        if (["DECIMAL","NUMBER"].includes(y.kind)) notes.push("数值轴为近似显示，悬浮提示、表格和 CSV 保留返回原值。");
        return {ok:true,type,x:xName,y:yName,points,notes,truncated:data.truncated===true,dateAxis:type==="line" && !NUMERIC.has(x.kind)};
    }
    const api={describe,recommend,prepare,numeric,dateValue,NUMERIC};
    if (typeof module!=="undefined" && module.exports) module.exports=api;
    else root.SqlfinderChartData=api;
})(typeof window!=="undefined" ? window : globalThis);
