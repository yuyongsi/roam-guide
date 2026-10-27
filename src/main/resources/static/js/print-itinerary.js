'use strict';
// 预览只存在于当前页面内；独立 iframe 只打印行程文档。
const tripPrint={dialog:null,token:0,owner:null,tripId:null,ready:false};
window.addEventListener('storage',event=>{
 if(event.key!=='roam-session-change'||!tripPrint.dialog)return;
 try{const value=JSON.parse(event.newValue);if(!value||typeof value.nonce!=='string'||!(value.uid===null||(Number.isSafeInteger(value.uid)&&value.uid>0)))return;if(value.uid!==Number(tripPrint.owner))clearTripPrint();}catch{}
});
function syncTripPrintRoute(path){if(tripPrint.dialog&&path!=='/trip/'+tripPrint.tripId)clearTripPrint();}
function clearTripPrint(){
 tripPrint.token++;tripPrint.ready=false;tripPrint.owner=null;tripPrint.tripId=null;
 if(tripPrint.dialog){tripPrint.dialog.querySelector('iframe')?.remove();if(tripPrint.dialog.open)tripPrint.dialog.close();tripPrint.dialog.remove();tripPrint.dialog=null;}
}
function printDocument(data,withBudget){
 const t=data.trip,s=data.schedule,meals=data.meals;
 const p=x=>esc(x), money=x=>budgetMoney(x);
 const mealHtml=m=>'<article><p><strong>'+p(m.slot)+' · '+p(m.title)+'</strong>（'+p(m.city_name)+'）'+(m.spot_id?' · 关联 '+p(m.spot_title||'原景点')+(!Number(m.spot_in_trip)?'（已移出行程）':''):'')+'</p>'+(m.notes?'<p>用餐备注：'+p(m.notes)+'</p>':'')+(withBudget?'<p>预计餐费：'+money(m.planned_cents)+'</p>':'')+'</article>';
 const days=Array.from({length:Number(t.days)},(_,n)=>{
  const day=n+1,rows=t.items.filter(i=>Number(i.day_number)===day),timing=s.days.find(d=>Number(d.day_number)===day),food=meals.filter(m=>Number(m.day_number)===day);
  return '<section><h2>第 '+day+' 天 <small>'+p(dayDate(t,day))+'</small></h2>'+'<p class="sub">'+p(timing.start_time)+' 出发'+(rows.length?' · 预计 '+p(timing.end_time)+' 结束'+(timing.crosses_midnight?'（跨日）':''):'')+'</p>'+(rows.length?rows.map((i,index)=>{const time=timing.stops.find(x=>Number(x.spot_id)===Number(i.spot_id));return '<article><h3>'+(index+1)+'. '+p(i.title)+' <small>'+p(i.city_name)+(Number(i.published)?'':' · 已下架')+'</small></h3>'+(time?'<p>'+p(time.arrival)+'—'+p(time.departure)+' · 到达前交通 '+p(time.travel_minutes)+' 分钟 · 游览后休息 '+p(time.rest_minutes)+' 分钟</p>':'')+(i.note?'<p>这一站的备注：'+p(i.note)+'</p>':'')+'</article>';}).join(''):'<p>当天尚未安排景点。</p>')+'<h3>用餐计划</h3>'+(food.length?food.map(mealHtml).join(''):'<p>尚未安排用餐。</p>')+'</section>';
 }).join('');
 const oldMeals=meals.filter(m=>Number(m.day_number)<1||Number(m.day_number)>Number(t.days));
 const oldMealsHtml=oldMeals.length?'<section><h2>原用餐安排（已超出当前行程天数）</h2>'+oldMeals.map(m=>'<h3>原第 '+p(m.day_number)+' 天</h3>'+mealHtml(m)).join('')+'</section>':'';
 let budget='';if(withBudget){const b=data.budget,x=b.summary;budget='<section><h2>旅行预算（人民币）</h2><p>'+p(b.people)+' 人同行 · 计划 '+money(x.planned_cents)+' · 实际 '+money(x.actual_cents)+' · '+(Number(x.balance_cents)<0?'超出':'余量')+' '+money(Math.abs(Number(x.balance_cents)))+'</p>'+(b.notes?'<p>预算备注：'+p(b.notes)+'</p>':'')+'<h3>费用明细</h3>'+(b.entries.length?b.entries.map(e=>'<article><p><strong>'+p(e.label)+'</strong> · '+p(e.category)+' · '+p(budgetRef(e))+'</p><p>计划 '+money(e.planned_cents)+' / 实际 '+money(e.actual_cents)+'</p></article>').join(''):'<p>尚未记录费用。</p>')+'</section>';}
 return '<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>行程单</title><style>@page{size:A4;margin:16mm}*{box-sizing:border-box}html,body{background:#fff;color:#111}body{font:14px/1.65 "Microsoft YaHei",sans-serif;margin:0 auto;max-width:178mm;padding:12mm;overflow-wrap:anywhere}h1{font-size:25px;line-height:1.3;margin:0 0 8px}h2{font-size:19px;border-bottom:1px solid #333;padding-bottom:5px;margin:24px 0 8px;break-after:avoid}h3{font-size:15px;margin:12px 0 3px;break-after:avoid}small,.sub{font-size:12px;color:#333}p{margin:4px 0;white-space:pre-wrap}article{margin:8px 0;padding-left:10px;border-left:2px solid #aaa}footer{margin-top:24px;border-top:1px solid #777;padding-top:8px;font-size:12px}@media print{body{padding:0;max-width:none}section{break-inside:auto}h2,h3{break-after:avoid}p{orphans:2;widows:2}}</style></head><body><header><h1>'+p(t.title)+'</h1><p>'+p(t.days)+' 天 · '+(t.start_date?p(t.start_date)+' 出发':'出发日期待定')+'</p><p>私人行程单 · 包含行程及景点备注、用餐备注和行前清单'+(withBudget?'、预算':'；不含预算')+'</p>'+(t.notes?'<p>行程备忘：'+p(t.notes)+'</p>':'')+'</header>'+days+'<section><h2>行前清单</h2>'+((t.checklist||[]).length?t.checklist.map(i=>'<p>'+(i.done?'☑':'☐')+' '+p(i.text)+'</p>').join(''):'<p>暂无事项。</p>')+'</section>'+oldMealsHtml+budget+'<footer>时间为行程计划，游览时长依据景点编辑建议；交通与休息由用户填写，0 分钟表示未另计。出发前请核对交通、预约与开放信息。</footer></body></html>';
}
async function openTripPrint(t){
 if(!t||!state.user||location.pathname!=='/trip/'+t.id)throw new Error('请重新打开自己的行程后再打印');
 if(formSafety.hasDirtyDialogs()){toast('请先保存或关闭尚未保存的编辑内容，再打印行程。',true);return;}
 clearTripPrint();tripPrint.owner=state.user.id;tripPrint.tripId=t.id;
 const d=document.createElement('dialog');d.className='modal print-dialog';d.innerHTML='<h2>打印行程 / 保存 PDF</h2><p class="small-text">预览包含私人行程和景点备注、用餐备注及行前清单，请留意分享范围。时间和交通为计划，不是实时信息。</p><div class="print-options"><label><input type="checkbox" data-print-budget>包含预算及预计餐费</label><span class="small-text">默认不包含金额和预算备注</span></div><p class="form-error" role="alert" aria-live="polite"></p><iframe title="行程单预览" sandbox="allow-same-origin allow-modals"></iframe><div class="button-row"><button type="button" class="button secondary" data-print-close>关闭</button><button type="button" class="button" data-print-now disabled>打印 / 保存 PDF</button></div>';
 document.body.append(d);tripPrint.dialog=d;d.addEventListener('close',()=>{if(tripPrint.dialog===d)clearTripPrint();});d.querySelector('[data-print-close]').addEventListener('click',()=>clearTripPrint());d.querySelector('[data-print-budget]').addEventListener('change',()=>void loadTripPrint());d.querySelector('[data-print-now]').addEventListener('click',()=>void printTripNow());d.showModal();await loadTripPrint();
}
async function loadTripPrint(){
 const d=tripPrint.dialog,token=++tripPrint.token,owner=tripPrint.owner,id=tripPrint.tripId,checked=d.querySelector('[data-print-budget]').checked,frame=d.querySelector('iframe'),button=d.querySelector('[data-print-now]'),error=d.querySelector('.form-error');tripPrint.ready=false;button.disabled=true;frame.srcdoc='';error.textContent='正在读取已保存的行程…';
 try{const data=await api('/api/me/trips/'+id+'/print?budget='+checked);if(token!==tripPrint.token||tripPrint.dialog!==d||Number(state.user?.id)!==Number(owner)||location.pathname!=='/trip/'+id)return;frame.srcdoc=printDocument(data,checked);tripPrint.ready=true;button.disabled=false;error.textContent='';}
 catch(e){if(token===tripPrint.token&&tripPrint.dialog===d){error.textContent='预览加载失败：'+e.message+'。请切换预算选项重试，或关闭后重新打开。';}}
}
async function printTripNow(){
 const d=tripPrint.dialog,owner=tripPrint.owner,token=tripPrint.token;if(!d||!tripPrint.ready)return;
 const button=d.querySelector('[data-print-now]');button.disabled=true;
 try{await refreshSession();if(tripPrint.dialog!==d||token!==tripPrint.token||Number(state.user?.id)!==Number(owner))return;const frame=d.querySelector('iframe');if(!frame?.contentWindow||!frame.contentDocument?.body?.textContent.trim())throw new Error('预览尚未加载完成，请稍后重试');frame.contentWindow.focus();frame.contentWindow.print();}
 catch(e){if(tripPrint.dialog===d)d.querySelector('.form-error').textContent=e.message;}
 finally{if(tripPrint.dialog===d&&tripPrint.ready)button.disabled=false;}
}
