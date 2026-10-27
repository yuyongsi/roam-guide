'use strict';
const budgetUi={data:null,tripId:null,busy:false,draft:null,editorToken:0,filterDay:null,filterCategory:null};
const budgetCategories=['交通','住宿','餐饮','门票','购物','其他'];
function budgetMoney(cents){return '¥'+(Number(cents||0)/100).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2});}
function budgetOptions(selected){return budgetCategories.map(c=>'<option '+(selected===c?'selected':'')+'>'+c+'</option>').join('');}
async function renderTripBudget(tripId,renderVersion=state.renderId){
 const data=await api('/api/me/trips/'+tripId+'/budget');
 if(Number(budgetUi.tripId)!==Number(tripId)){budgetUi.filterDay=null;budgetUi.filterCategory=null;}
 if(renderVersion===state.renderId){budgetUi.data=data;budgetUi.tripId=Number(tripId);}
 return budgetPanel(data);
}
function budgetRef(e){return (Number(e.day_number)?'第 '+Number(e.day_number)+' 天'+(e.day_valid===false?'（已超出当前行程）':''):'未分配日期')+(Number(e.spot_id)?' · '+(e.spot_title||'原关联景点')+(!Number(e.spot_in_trip)?'（已移出行程）':''):'');}
function budgetPanel(d){
 const s=d.summary,over=Number(s.balance_cents)<0,used=Number(s.actual_cents),entries=d.entries,visible=entries.filter(e=>(budgetUi.filterDay===null||Number(e.day_number)===Number(budgetUi.filterDay))&&(!budgetUi.filterCategory||e.category===budgetUi.filterCategory));
 const days=s.days||[],cats=s.categories.filter(c=>Number(c.planned_cents)||Number(c.actual_cents));
 return '<section class="trip-budget" aria-labelledby="tripBudgetHeading"><div class="budget-heading"><div><h2 id="tripBudgetHeading">旅行预算</h2><p>把出行花费记清楚，'+d.people+' 人同行。</p></div><div class="button-row"><button class="button secondary small" data-budget="settings">人数与备注</button><button class="button small" data-budget="add">'+icon('plus',16)+'添加费用</button></div></div><div class="budget-totals"><div><span>计划预算</span><strong>'+budgetMoney(s.planned_cents)+'</strong><small>人均 '+budgetMoney(s.per_person_planned_cents)+'</small></div><div><span>实际支出</span><strong>'+budgetMoney(s.actual_cents)+'</strong><small>人均 '+budgetMoney(s.per_person_actual_cents)+'</small></div><div class="'+(over?'budget-over':'')+'"><span>'+(over?'超出预算':'预算余量')+'</span><strong>'+budgetMoney(Math.abs(s.balance_cents))+'</strong><small>'+(over?'实际支出超过计划预算':'计划预算减去实际支出')+'</small></div></div>'+(d.notes?'<p class="budget-notes">'+esc(d.notes)+'</p>':'')+
 (entries.length?'<div class="budget-insights"><section><h3>每天花费</h3><p class="small-text muted">点击日期，查看对应明细。</p><div class="budget-day-grid">'+days.map(x=>'<button class="budget-day '+(budgetUi.filterDay===Number(x.day_number)?'active':'')+'" data-budget="filter-day" data-day="'+x.day_number+'"><strong>'+(Number(x.day_number)?'第 '+x.day_number+' 天'+(!x.valid?'（原安排）':''):'未分配日期')+'</strong><span>计划 '+budgetMoney(x.planned_cents)+'</span><span>实际 '+budgetMoney(x.actual_cents)+'</span></button>').join('')+'</div></section><section class="budget-category-compare"><h3>分类预算与实际</h3>'+cats.map(c=>{const scale=Math.max(...cats.flatMap(x=>[Number(x.planned_cents),Number(x.actual_cents)]),1),delta=Number(c.actual_cents)-Number(c.planned_cents);return '<button data-budget="filter-category" data-category="'+esc(c.category)+'" class="budget-compare-row '+(budgetUi.filterCategory===c.category?'active':'')+'"><span>'+esc(c.category)+'</span><div class="budget-compare-bars"><i style="width:'+(Number(c.planned_cents)/scale*100)+'%" title="计划 '+budgetMoney(c.planned_cents)+'"></i><i style="width:'+(Number(c.actual_cents)/scale*100)+'%" title="实际 '+budgetMoney(c.actual_cents)+'"></i></div><small>计划 '+budgetMoney(c.planned_cents)+' / 实际 '+budgetMoney(c.actual_cents)+(delta>0?' · 超支 '+budgetMoney(delta):'')+'</small></button>';}).join('')+'<p class="small-text muted">浅色为计划，深色为实际；点击分类查看明细。</p></section></div><div class="budget-filter-caption"><span>费用明细 · '+visible.length+' 项'+(budgetUi.filterDay!==null?' · '+(budgetUi.filterDay?'第 '+budgetUi.filterDay+' 天':'未分配日期'):'')+(budgetUi.filterCategory?' · '+esc(budgetUi.filterCategory):'')+'</span>'+((budgetUi.filterDay!==null||budgetUi.filterCategory)?'<button class="text-button" data-budget="clear-filter">显示全部费用</button>':'')+'</div><div class="budget-table-wrap"><table class="budget-table"><thead><tr><th>费用项目与关联</th><th>分类</th><th class="money-cell">预算</th><th class="money-cell">实际</th><th>操作</th></tr></thead><tbody>'+visible.map(e=>'<tr><td>'+esc(e.label)+'<small class="budget-reference">'+esc(budgetRef(e))+'</small></td><td>'+esc(e.category)+'</td><td class="money-cell">'+budgetMoney(e.planned_cents)+'</td><td class="money-cell">'+budgetMoney(e.actual_cents)+'</td><td><div class="table-actions"><button data-budget="edit" data-key="'+esc(e.key)+'">编辑</button><button data-budget="remove" data-key="'+esc(e.key)+'">移除</button></div></td></tr>').join('')+'</tbody></table>'+(!visible.length?'<p class="budget-empty">这个筛选下暂无费用，可清除筛选查看全部。</p>':'')+'</div>':'<div class="budget-empty"><p>还没有费用记录。把交通、住宿、餐饮等分项记下来，安排会更清楚。</p><button class="text-button" data-budget="add">添加第一笔费用</button></div>')+'<p class="budget-footnote">金额由你填写，以人民币计算；人均金额按同行人数分摊并四舍五入到分。预算会随行程清单一起导出。调整行程后，旧费用保留，关联失效时可重新选择。</p></section>';
}
function budgetRefFields(d,e){const day=Number(e.day_number||0),spot=Number(e.spot_id||0);return '<div class="row"><div><label for="budgetDay">关联日期（可选）</label><select id="budgetDay" name="day_number"><option value="0">未分配日期</option>'+Array.from({length:Number(d.trip_days)},(_,i)=>'<option value="'+(i+1)+'" '+(day===i+1?'selected':'')+'>第 '+(i+1)+' 天</option>').join('')+(day>Number(d.trip_days)?'<option value="'+day+'" selected>原第 '+day+' 天（需重新安排）</option>':'')+'</select></div><div><label for="budgetSpot">关联景点（可选）</label><select id="budgetSpot" name="spot_id"><option value="0">不关联景点</option>'+d.trip_spots.map(x=>'<option value="'+x.spot_id+'" '+(spot===Number(x.spot_id)?'selected':'')+'>'+esc(x.title)+'（第 '+x.day_number+' 天）</option>').join('')+(spot&&!d.trip_spots.some(x=>Number(x.spot_id)===spot)?'<option value="'+spot+'" selected>'+esc(e.spot_title||'原景点')+'（已移出行程）</option>':'')+'</select></div></div>';}

function budgetEditor(kind,key){
 const data=structuredClone(budgetUi.data);budgetUi.draft=data;const token=++budgetUi.editorToken;
 let fields='',heading='';
 if(kind==='settings'){heading='同行人数与预算备注';fields='<label for="budgetPeople">同行人数</label><input id="budgetPeople" type="number" name="people" min="1" max="99" required value="'+data.people+'"><label for="budgetNotes">预算备注（可选）</label><textarea id="budgetNotes" name="notes" maxlength="500" rows="3" placeholder="例如：住宿为两人合住的总价">'+esc(data.notes)+'</textarea>';}
 else{const e=key?data.entries.find(i=>i.key===key):{key:crypto.randomUUID(),label:'',category:'交通',planned:'0.00',actual:'0.00',day_number:0,spot_id:0};if(!e)throw new Error('没有找到这项费用，请刷新后再试');key=e.key;heading=kind==='add'?'添加一笔费用':'编辑费用';fields=budgetRefFields(data,e)+'<label for="budgetLabel">费用项目</label><input id="budgetLabel" name="label" maxlength="80" required value="'+esc(e.label)+'" placeholder="例如：往返车票、两晚住宿"><label for="budgetCategory">费用分类</label><select id="budgetCategory" name="category">'+budgetOptions(e.category)+'</select><div class="row"><div><label for="budgetPlanned">计划预算（元）</label><input id="budgetPlanned" name="planned" type="number" min="0" max="1000000" step="0.01" required value="'+esc(e.planned)+'"></div><div><label for="budgetActual">实际支出（元）</label><input id="budgetActual" name="actual" type="number" min="0" max="1000000" step="0.01" required value="'+esc(e.actual)+'"></div></div><p class="form-help">填写全部同行人的合计金额，人均数值会自动计算。尚未发生的实际支出可填 0。</p>';}
 featureModal('<h2>'+heading+'</h2><form id="budgetForm" class="form-stack budget-form" data-budget-form="'+kind+'" data-key="'+esc(key||'')+'" data-token="'+token+'" data-trip="'+data.trip_id+'">'+fields+'<p class="form-error" role="alert"></p><div class="button-row"><button class="button" type="submit">保存预算</button><button class="button secondary" type="button" data-close="featureDialog">取消</button></div></form>');
}
async function writeBudget(d){
 const version=state.renderId;const out=await api('/api/me/trips/'+d.trip_id+'/budget',{method:'PUT',body:{revision:d.revision,people:d.people,notes:d.notes,entries:d.entries.map(e=>({key:e.key,label:e.label,category:e.category,planned:e.planned,actual:e.actual,day_number:Number(e.day_number||0),spot_id:Number(e.spot_id||0)}))}});
 if(version===state.renderId&&Number(budgetUi.tripId)===Number(d.trip_id))budgetUi.data=out;return out;
}
async function budgetAction(button){
 const action=button.dataset.budget;if(!action)return false;if(budgetUi.busy)return true;
 if(!budgetUi.data||location.pathname!=='/trip/'+budgetUi.tripId)throw new Error('请刷新当前行程后再编辑预算');
 if(action==='filter-day'){budgetUi.filterDay=budgetUi.filterDay===Number(button.dataset.day)?null:Number(button.dataset.day);$('.trip-budget').outerHTML=budgetPanel(budgetUi.data);return true;}
 if(action==='filter-category'){budgetUi.filterCategory=budgetUi.filterCategory===button.dataset.category?null:button.dataset.category;$('.trip-budget').outerHTML=budgetPanel(budgetUi.data);return true;}
 if(action==='clear-filter'){budgetUi.filterDay=null;budgetUi.filterCategory=null;$('.trip-budget').outerHTML=budgetPanel(budgetUi.data);return true;}
 if(action==='add'){if(budgetUi.data.entries.length>=80)throw new Error('每份行程最多保留 80 项费用');budgetEditor('add');}
 else if(action==='edit')budgetEditor('edit',button.dataset.key);
 else if(action==='settings')budgetEditor('settings');
 else if(action==='remove'){
  budgetUi.busy=true;button.disabled=true;try{const d=structuredClone(budgetUi.data),item=d.entries.find(i=>i.key===button.dataset.key);if(!item)throw new Error('这项费用已不存在，请刷新后再试');if(await confirmAction('从本次行程预算中移除“'+item.label+'”？','移除费用？')){d.entries=d.entries.filter(i=>i.key!==item.key);await writeBudget(d);await render();toast('费用已移除');}}finally{budgetUi.busy=false;button.disabled=false;}
 }
 return true;
}
async function submitBudgetForm(form,event){
 const kind=form.dataset.budgetForm;if(!kind)return false;event.preventDefault();const button=form.querySelector('[type=submit]'),box=form.querySelector('.form-error');if(button.disabled)return true;button.disabled=true;box.textContent='';
 try{
  if(Number(form.dataset.token)!==budgetUi.editorToken||location.pathname!=='/trip/'+form.dataset.trip)throw new Error('页面已切换，请关闭窗口后重新编辑预算');
  const data=Object.fromEntries(new FormData(form)),d=structuredClone(budgetUi.draft);
  if(kind==='settings'){d.people=Number(data.people);d.notes=data.notes;}
  else{const entry={key:form.dataset.key,label:data.label,category:data.category,planned:data.planned,actual:data.actual,day_number:Number(data.day_number||0),spot_id:Number(data.spot_id||0)};if(kind==='add')d.entries.push(entry);else{const at=d.entries.findIndex(i=>i.key===entry.key);if(at<0)throw new Error('未找到费用项目，请刷新后再试');d.entries[at]=entry;}}
  await writeBudget(d);$('#featureDialog').close();await render();toast('预算已保存');
 }catch(error){box.textContent=error.message;}
 finally{button.disabled=false;}
 return true;
}
async function tripBudgetExport(tripId){
 const d=await api('/api/me/trips/'+tripId+'/budget'),s=d.summary;
 let out='\n旅行预算（人民币）\n同行人数：'+d.people+' 人\n计划预算：'+budgetMoney(s.planned_cents)+'，实际支出：'+budgetMoney(s.actual_cents)+'\n'+(s.balance_cents<0?'超出预算：':'预算余量：')+budgetMoney(Math.abs(s.balance_cents))+'\n人均预算：'+budgetMoney(s.per_person_planned_cents)+'，人均实际：'+budgetMoney(s.per_person_actual_cents)+'\n';
 if(d.notes)out+='预算备注：'+d.notes+'\n';
 out+='\n费用明细\n'+(d.entries.length?d.entries.map((e,i)=>(i+1)+'. '+e.label+'（'+e.category+'；'+budgetRef(e)+'） 预算 '+budgetMoney(e.planned_cents)+' / 实际 '+budgetMoney(e.actual_cents)).join('\n'):'尚未记录费用')+'\n';
 if(d.entries.length)out+='\n分类合计\n'+s.categories.filter(c=>c.planned_cents||c.actual_cents).map(c=>c.category+'：预算 '+budgetMoney(c.planned_cents)+' / 实际 '+budgetMoney(c.actual_cents)).join('\n')+'\n';
 return out;
}
