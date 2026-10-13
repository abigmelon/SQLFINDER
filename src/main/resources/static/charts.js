/* 查询图表展示 */
"use strict";
(() => {
    const el=id=>document.getElementById(id);
    const names={table:"表格",bar:"柱状图",line:"折线图",pie:"饼图"};
    const palette=["#afdac0","#85b7da","#d8b77f","#c49dcc","#d79283","#9db6a0","#a3a9dc","#d0c5ae","#78bdb8","#cbabb1","#aaba83","#b6bcc4"];
    // richText tooltips never accept HTML; neutralize its formatting delimiters in user values.
    const plain=value=>String(value).replaceAll("{","﹛").replaceAll("}","﹜");
    const short=value=>String(value).length>22 ? String(value).slice(0,21)+"…" : String(value);
    function create() {
        let answer=null,chart=null,type="table",plan=null;
        const controls=[...document.querySelectorAll("[data-result-view]")];
        function dispose() { if (chart) { chart.dispose(); chart=null; } el("chart-canvas").replaceChildren(); }
        function clear() {
            dispose(); answer=null;plan=null;type="table";
            el("chart-panel").hidden=true; el("chart-controls").hidden=true;
            el("chart-note").textContent=""; el("chart-error").textContent="";
            el("chart-x").replaceChildren(); el("chart-y").replaceChildren(); el("chart-recommendation").textContent="";
            el("recommend-chart").disabled=true; el("export-chart").disabled=true;
            for (const button of controls) button.setAttribute("aria-pressed",String(button.dataset.resultView==="table"));
        }
        function selectOptions(id,columns,selected) {
            el(id).replaceChildren(...columns.map(c=>{const option=document.createElement("option");option.value=c.name;option.textContent=c.name;return option;}));
            if (columns.some(c=>c.name===selected)) el(id).value=selected;
        }
        function load(data) {
            clear();answer=data;
            plan=window.SqlfinderChartData?.recommend(data);
            if (!plan) {el("chart-recommendation").textContent="图表组件未加载，可继续使用表格和 CSV。";return;}
            const columns=plan.columns;
            selectOptions("chart-x",columns.filter(c=>!["BINARY","OTHER"].includes(c.kind)),plan.x);
            selectOptions("chart-y",columns.filter(c=>window.SqlfinderChartData.NUMERIC.has(c.kind)),plan.y);
            el("chart-recommendation").textContent=plan.type==="table" ? plan.reason : `推荐${names[plan.type]} · ${plan.x} / ${plan.y}`;
            el("recommend-chart").disabled=plan.type==="table";
        }
        function view(next) {
            if (!answer) return;
            type=next; dispose();
            for (const button of controls) button.setAttribute("aria-pressed",String(button.dataset.resultView===type));
            const table=type==="table";
            for (const id of ["table-toolbar","table-view"]) el(id).hidden=!table;
            el("chart-controls").hidden=table;el("chart-panel").hidden=table;
            el("chart-note").textContent="";el("chart-error").textContent="";el("chart-error").hidden=true;
            el("chart-canvas").hidden=false;el("export-chart").disabled=true;
            if (table) return;
            try {
                if (!window.SqlfinderChartData || !window.echarts) throw new Error("图表组件未加载，请切回表格查看结果。");
                const prepared=window.SqlfinderChartData.prepare(answer,type,el("chart-x").value,el("chart-y").value);
                if (!prepared.ok) throw new Error(prepared.reason);
                el("chart-note").textContent=prepared.notes.join(" ");
                chart=window.echarts.init(el("chart-canvas"),null,{renderer:"canvas",devicePixelRatio:Math.min(2,window.devicePixelRatio||1)});
                chart.setOption(options(prepared));
                el("export-chart").disabled=false;
                el("chart-canvas").setAttribute("aria-label",`${names[type]}：${prepared.x} / ${prepared.y}，${prepared.points.length} 行。完整数值可切回表格查看。`);
            } catch (error) {
                dispose(); el("chart-canvas").hidden=true;
                el("chart-error").hidden=false;el("chart-error").textContent=error.message || "图表暂时无法绘制，请切回表格查看结果。";
            }
            if (window.matchMedia("(max-width: 760px)").matches) el("chart-controls").scrollIntoView({block:"start"});
            else el("chart-panel").scrollIntoView({block:"nearest"});
        }
        for (const button of controls) button.addEventListener("click",()=>view(button.dataset.resultView));
        el("recommend-chart").addEventListener("click",()=>{if (plan?.type!=="table") view(plan.type);});
        for (const id of ["chart-x","chart-y"]) el(id).addEventListener("change",()=>view(type));
        const resize=new ResizeObserver(()=>{if (chart) chart.resize();});resize.observe(el("chart-canvas"));
        el("export-chart").addEventListener("click",()=>{
            if (!chart || !answer) return;
            try {
                const url=chart.getDataURL({type:"png",pixelRatio:2,backgroundColor:"#191a1c"});
                const link=document.createElement("a");link.href=url;link.download=`sqlfinder-${type}-${new Date().toISOString().slice(0,10)}.png`;link.click();
            } catch {el("chart-error").hidden=false;el("chart-error").textContent="图片导出失败，可继续查看图表或导出 CSV。";}
        });
        return {load,clear};
    }
    function options(p) {
        const labelColor="#a4aba8",gridColor="#393c40";
        const tooltip={trigger:"item",renderMode:"richText",confine:true,backgroundColor:"#222427",borderColor:gridColor,
            textStyle:{color:"#eceeed",fontFamily:"Segoe UI, Microsoft YaHei, sans-serif"},
            formatter:item=>`${plain(p.x)}：${plain(item.data.label)}\n${plain(p.y)}：${plain(item.data.exact)}`
                +(p.type==="pie" ? `\n占比：${item.percent.toFixed(1)}%（本次结果）` : "")};
        const common={animation:false,backgroundColor:"transparent",color:palette,tooltip,
            title:{left:12,top:4,text:`${short(p.y)} / ${short(p.x)}`,textStyle:{color:"#eceeed",fontSize:12,fontWeight:500},
                subtext:p.type==="pie" ? `占比仅限本次返回的 ${p.points.length} 行` : `本次返回 ${p.points.length} 行${p.truncated ? " · 结果已截断" : ""}${p.points.length>15 ? " · 可缩放视窗" : ""}`,
                subtextStyle:{color:labelColor,fontSize:10}},
            textStyle:{fontFamily:"Segoe UI, Microsoft YaHei, sans-serif"},
            aria:{enabled:true,label:{description:`${names[p.type]}，维度 ${p.x}，数值 ${p.y}。完整数据可切回表格查看。`}}};
        if (p.type==="pie") return {...common,legend:{type:"scroll",bottom:4,textStyle:{color:labelColor},formatter:short},
                series:[{type:"pie",radius:["32%","58%"],center:["50%","50%"],minAngle:0,avoidLabelOverlap:true,
                label:{show:false},itemStyle:{borderColor:"#191a1c",borderWidth:2},
                data:p.points.map(point=>({...point,name:point.label,value:point.value}))}]};
        const axis={axisLine:{lineStyle:{color:gridColor}},axisTick:{show:false},axisLabel:{color:labelColor,fontSize:11},
            nameTextStyle:{color:labelColor,fontSize:11},splitLine:{lineStyle:{color:gridColor,type:"dashed"}}};
        const calendarOnly=p.dateAxis && p.points.every(point=>/^\d{4}-\d{2}(?:-\d{2})?$/.test(point.label));
        return {...common,useUTC:true,grid:{top:64,left:18,right:22,bottom:56,containLabel:true},
            xAxis:p.type==="bar" ? {...axis,type:"category",data:p.points.map(point=>point.label),splitLine:{show:false},axisLabel:{...axis.axisLabel,formatter:short,hideOverlap:true}}
                : {...axis,type:p.dateAxis ? "time" : "value",scale:true,minInterval:calendarOnly ? 86400000 : undefined,
                    axisLabel:{...axis.axisLabel,hideOverlap:true,
                        formatter:p.dateAxis ? value=>{const date=new Date(value).toISOString();return calendarOnly ? date.slice(0,10) : date.slice(0,10)+"\n"+date.slice(11,16);} : undefined}},
            yAxis:{...axis,type:"value",scale:false},
            dataZoom:p.points.length>15 ? [{type:"slider",xAxisIndex:0,bottom:4,height:16,borderColor:gridColor,backgroundColor:"#222427",
                fillerColor:"#afdac025",textStyle:{color:labelColor},handleStyle:{color:"#afdac0"},filterMode:"none",showDetail:false}] : [],
            series:[p.type==="bar" ? {type:"bar",barMaxWidth:42,itemStyle:{color:palette[0]},
                data:p.points.map(point=>({...point,value:point.value}))}
                : {type:"line",connectNulls:false,smooth:false,showSymbol:true,symbolSize:7,lineStyle:{color:palette[1],width:2},itemStyle:{color:palette[1]},
                    data:p.points.map(point=>({...point,value:[point.axis,point.value]}))}]};
    }
    window.SqlfinderCharts={create};
})();
