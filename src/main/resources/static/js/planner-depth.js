'use strict';
const plannerDepthState={trip:null,schedule:null,undo:null,busy:false,drag:null,bound:null,undoing:false,editor:null};
function rememberPlannerChange(before,after){
 if(plannerDepthState.undoing)return;
 if(!before||Number(before.id)!==Number(after.id)||!state.user)return;
 plannerDepthState.undo={tripId:Number(after.id),userId:Number(state.user.id),before:structuredClone(before),afterRevision:Number(after.revision)};
}
async function renderPlannerDepth(t,version=state.renderId){
 const schedule=await api('/api/me/trips/'+t.id+'/schedule');
 if(Number(schedule.trip_revision)!==Number(t.revision)){const error=new Error('行程刚刚在其他窗口发生变化，请重新加载后继续安排');error.status=409;throw error;}
 if(version===state.renderId){plannerDepthState.trip=t;plannerDepthState.schedule=schedule;
  const u=plannerDepthState.undo;if(u&&(u.tripId!==Number(t.id)||u.userId!==Number(state.user?.id)||u.afterRevision!==Number(t.revision)))plannerDepthState.undo=null;
 }
 return '<div class="planner-depth-toolbar"><div><strong>把每天的节奏安排清楚</strong><p>拖动景点把手调整顺序或移到另一天；也可以继续使用上移、下移和改天数。</p></div><button class="button secondary small" data-planner-depth="undo" '+(!plannerDepthState.undo?'disabled':'')+'>撤销上一步行程修改</button></div>';
}
function plannerTimeLine(d){
 return '<div class="planner-time-summary"><div><strong>'+esc(d.start_time)+' 出发</strong><span>'+(d.stops.length?'计划 '+esc(d.end_time)+' 结束':'当天还没有景点')+'</span><span>游览 '+d.visit_minutes+' 分钟 / 交通 '+d.travel_minutes+' 分钟 / 休息 '+d.rest_minutes+' 分钟</span></div><button class="text-button" data-planner-depth="time" data-day="'+d.day_number+'">设置当天时间</button></div>'+(d.crosses_midnight?'<p class="planner-time-warning">当前安排延续到次日，建议减少当天景点或调整停留安排。</p>':'');
}
function bindPlannerDepth(){
 const t=plannerDepthState.trip,s=plannerDepthState.schedule,main=$('#main');
 if(!t||!s||location.pathname!=='/trip/'+t.id||!main.querySelector('.planner-days'))return;
 for(const d of s.days){
  const section=main.querySelector('#trip-day-'+d.day_number);if(!section)continue;
  section.dataset.plannerDay=d.day_number;
  if(!section.querySelector('.planner-time-summary'))section.querySelector('.day-heading').insertAdjacentHTML('afterend',plannerTimeLine(d));
  for(const stop of d.stops){
   const card=section.querySelector('[data-stop="'+stop.spot_id+'"]');if(!card||card.querySelector('.planner-drag-handle'))continue;
   card.querySelector('img')?.setAttribute('draggable','false');
   card.querySelector('.stop-actions').insertAdjacentHTML('afterbegin','<button type="button" draggable="true" class="planner-drag-handle" data-planner-drag="'+stop.spot_id+'" data-planner-depth="drag-help" aria-label="拖动'+esc(stop.title)+'调整日期或顺序" title="按住拖动到景点前后或其他日期">⠿ 拖动</button>');
   card.querySelector('.stop-info').insertAdjacentHTML('afterbegin','<p class="planner-stop-time"><strong>'+esc(stop.arrival)+' — '+esc(stop.departure)+'</strong><span>到达前交通 '+stop.travel_minutes+' 分钟'+(stop.rest_minutes?'，游览后休息 '+stop.rest_minutes+' 分钟':'')+'</span></p>');
  }
 }
 if(plannerDepthState.bound===main)return;plannerDepthState.bound=main;
 main.addEventListener('dragstart',e=>{
  const handle=e.target.closest('[data-planner-drag]');if(!handle)return;
  if(plannerDepthState.busy||featureState.busy){e.preventDefault();return;}
  plannerDepthState.drag={spotId:Number(handle.dataset.plannerDrag),tripId:Number(featureState.trip.id),revision:Number(featureState.trip.revision)};
  e.dataTransfer.effectAllowed='move';e.dataTransfer.setData('text/plain',String(handle.dataset.plannerDrag));handle.closest('.trip-stop').classList.add('planner-is-dragging');
 });
 main.addEventListener('dragover',e=>{
  if(!plannerDepthState.drag)return;const section=e.target.closest('[data-planner-day]');if(!section)return;
  e.preventDefault();e.dataTransfer.dropEffect='move';plannerClearDropMarks();
  const card=e.target.closest('.trip-stop');if(card&&Number(card.dataset.stop)!==plannerDepthState.drag.spotId){const after=e.clientY>card.getBoundingClientRect().top+card.getBoundingClientRect().height/2;card.classList.add(after?'planner-drop-after':'planner-drop-before');}else section.classList.add('planner-drop-day');
 });
 main.addEventListener('drop',async e=>{
  const drag=plannerDepthState.drag,section=e.target.closest('[data-planner-day]');if(!drag||!section)return;
  e.preventDefault();const card=e.target.closest('.trip-stop'),after=card?e.clientY>card.getBoundingClientRect().top+card.getBoundingClientRect().height/2:false;
  const target=card?Number(card.dataset.stop):0,day=Number(section.dataset.plannerDay);plannerDepthState.drag=null;plannerClearDropMarks();
  if(target===drag.spotId)return;
  try{await plannerMoveStop(drag,day,target,after);}catch(error){toast(error.message,true);if(error.status===409)await render();}
 });
 main.addEventListener('dragend',()=>{plannerDepthState.drag=null;plannerClearDropMarks();});
}
function plannerClearDropMarks(){document.querySelectorAll('.planner-drop-before,.planner-drop-after,.planner-drop-day,.planner-is-dragging').forEach(e=>e.classList.remove('planner-drop-before','planner-drop-after','planner-drop-day','planner-is-dragging'));}
async function plannerMoveStop(drag,day,target,after){
 if(plannerDepthState.busy||featureState.busy)return;
 const t=featureState.trip;if(!t||Number(t.id)!==drag.tripId||Number(t.revision)!==drag.revision)throw new Error('行程已变更，请在当前安排上重新拖动');
 const next=structuredClone(t),moving=next.items.find(i=>Number(i.spot_id)===drag.spotId);if(!moving)return;
 const previous=JSON.stringify(next.items.map(i=>[i.spot_id,i.day_number]));moving.day_number=day;
 const groups=Array.from({length:t.days},(_,n)=>next.items.filter(i=>Number(i.day_number)===n+1&&Number(i.spot_id)!==drag.spotId));
 const group=groups[day-1];let at=target?group.findIndex(i=>Number(i.spot_id)===target):group.length;if(at<0)at=group.length;else if(target&&after)at++;
 group.splice(at,0,moving);next.items=groups.flat();if(JSON.stringify(next.items.map(i=>[i.spot_id,i.day_number]))===previous)return;
 plannerDepthState.busy=true;try{await saveTrip(next);await render();toast('景点已移到第 '+day+' 天，时间安排已更新');}finally{plannerDepthState.busy=false;}
}
function plannerTimeEditor(day){
 const d=plannerDepthState.schedule.days.find(x=>Number(x.day_number)===day);if(!d)return;
 plannerDepthState.editor={tripId:Number(plannerDepthState.trip.id),day,schedule:structuredClone(plannerDepthState.schedule)};
 featureModal('<h2>第 '+day+' 天的时间安排</h2><p class="muted small-text">交通与休息由你填写；游览时长使用景点编辑建议。0 分钟表示未另计，请结合实际行程补充。</p><form class="form-stack" data-planner-depth-form="time"><label for="plannerStartTime">当天出发时间<input id="plannerStartTime" name="start_time" type="time" required value="'+esc(d.start_time)+'"></label><div class="planner-time-editor">'+d.stops.map(s=>'<fieldset><legend>'+esc(s.title)+'</legend><p class="muted small-text">建议游览 '+s.visit_minutes+' 分钟</p><div class="row"><label for="plannerTravel'+s.spot_id+'">到达前交通（分钟）<input id="plannerTravel'+s.spot_id+'" type="number" name="travel_'+s.spot_id+'" min="0" max="360" step="1" required value="'+s.travel_minutes+'"></label><label for="plannerRest'+s.spot_id+'">游览后休息（分钟）<input id="plannerRest'+s.spot_id+'" type="number" name="rest_'+s.spot_id+'" min="0" max="360" step="1" required value="'+s.rest_minutes+'"></label></div></fieldset>').join('')+'</div><p class="form-error" role="alert"></p><button class="button" type="submit">保存当天时间</button></form>');
}
async function plannerDepthAction(button){
 const action=button.dataset.plannerDepth;if(!action)return false;if(plannerDepthState.busy)return true;button.disabled=true;
 try{
  if(action==='time')plannerTimeEditor(Number(button.dataset.day));
  else if(action==='drag-help')toast('按住这个把手拖动，可放到景点前后或另一日期；也可使用上移、下移或改天数。');
  else if(action==='undo'){
   const u=plannerDepthState.undo,t=featureState.trip;if(!u||!t||Number(t.id)!==u.tripId||Number(t.revision)!==u.afterRevision||Number(state.user?.id)!==u.userId)throw new Error('行程已继续修改，当前没有可以撤销的上一步');
   plannerDepthState.busy=true;plannerDepthState.undoing=true;
   try{const before=structuredClone(u.before);before.revision=u.afterRevision;await saveTrip(before);plannerDepthState.undo=null;await render();toast('已撤销上一步行程修改');}finally{plannerDepthState.undoing=false;plannerDepthState.busy=false;}
  }
 }catch(error){if(error.status===409)plannerDepthState.undo=null;toast(error.message,true);if(error.status===409)await render();}finally{button.disabled=false;}
 return true;
}
async function submitPlannerDepthForm(form,event){
 if(!form.dataset.plannerDepthForm)return false;event.preventDefault();const button=form.querySelector('[type=submit]'),box=form.querySelector('.form-error');if(button.disabled)return true;button.disabled=true;box.textContent='';
 try{const ed=plannerDepthState.editor;if(!ed)throw new Error('请重新打开当天时间设置');const data=Object.fromEntries(new FormData(form)),s=ed.schedule;
  const days=s.days.map(d=>({day_number:d.day_number,start_time:Number(d.day_number)===ed.day?data.start_time:d.start_time}));
  const stops=s.days.flatMap(d=>d.stops.map(i=>({spot_id:i.spot_id,travel_minutes:Number(d.day_number)===ed.day?Number(data['travel_'+i.spot_id]):i.travel_minutes,rest_minutes:Number(d.day_number)===ed.day?Number(data['rest_'+i.spot_id]):i.rest_minutes})));
  await api('/api/me/trips/'+ed.tripId+'/schedule',{method:'PUT',body:{revision:s.revision,trip_revision:s.trip_revision,days,stops}});$('#featureDialog').close();plannerDepthState.editor=null;await render();toast('当天时间已保存');
 }catch(error){box.textContent=error.message;}finally{button.disabled=false;}return true;
}
async function plannerDepthExport(id){
 const s=await api('/api/me/trips/'+id+'/schedule');let text='\n每日时间安排（用户计划）\n';
 for(const d of s.days){text+='\n第 '+d.day_number+' 天 '+d.start_time+' 出发，'+d.end_time+' 结束\n';text+=d.stops.map(i=>i.arrival+'—'+i.departure+' '+i.title+'；到达前交通 '+i.travel_minutes+' 分钟，游览后休息 '+i.rest_minutes+' 分钟').join('\n')+'\n';}
 return text+'交通与休息时间由用户填写；游览时长为编辑建议，0 分钟表示未另计。\n';
}
