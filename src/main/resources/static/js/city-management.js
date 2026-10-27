'use strict';
const cityUi={catalog:null,nonce:0,busy:false};
async function refreshCityLists(){
 const [all,publicCities]=await Promise.all([api('/api/admin/cities'),api('/api/cities')]);
 state.adminCities=all;state.cities=publicCities;return all;
}
async function cityManagementPage(){
 const rows=await api('/api/admin/cities');state.adminRows=rows;state.adminCities=rows;
 return '<div class="container admin-layout">'+profileSidebar(true,'cities')+'<section class="admin-main"><h1>目的地管理</h1><p class="muted small-text">从省份和城市目录新增目的地，补齐封面、介绍及景点后再展示。已有城市不会重复添加。</p><div class="admin-toolbar"><input id="adminFilter" type="search" placeholder="查找省份或城市" aria-label="查找目的地"><button class="button small" data-city="create">'+icon('plus',16)+'新增城市</button></div><div class="table-wrap"><table><thead><tr><th>目的地</th><th>介绍</th><th>状态</th><th>操作</th></tr></thead><tbody>'+rows.map(c=>'<tr data-admin-row data-keyword="'+esc(c.name+' '+c.province)+'"><td><strong>'+esc(c.name)+'</strong><div class="muted small-text">'+esc(c.province)+'</div></td><td><div class="table-intro">'+esc(c.description||'尚未填写城市介绍，先完善资料。')+'</div></td><td><span class="city-stage '+(Number(c.published)?'live':'draft')+'">'+(Number(c.published)?'已展示':'待完善')+'</span></td><td><div class="table-actions"><button data-city="edit" data-id="'+c.id+'">'+(Number(c.published)?'编辑资料':'完善资料')+'</button>'+(!Number(c.published)?'<button data-city="add-spot" data-id="'+c.id+'">添加景点</button><button data-city="publish" data-id="'+c.id+'" data-revision="'+c.revision+'">展示</button>':'')+'</div></td></tr>').join('')+'</tbody></table></div><p class="form-help">新城市先保存在后台；城市封面、短句、介绍与至少一个已发布景点准备好后，才会进入前台。</p></section></div>';
}
async function openCityCreate(){
 const nonce=++cityUi.nonce,user=state.user?.id,version=state.renderId;
 featureModal('<div data-city-loading><h2>新增城市</h2><p class="muted">正在读取省市目录…</p></div>');
 try{
  const catalog=await api('/api/admin/city-catalog');
  if(nonce!==cityUi.nonce||state.user?.id!==user||!$('#featureDialog').open||!$('#featureDialog [data-city-loading]'))return;
  if(version!==state.renderId){$('#featureDialog').close();return;}
  cityUi.catalog=catalog;
  featureModal('<h2>新增城市</h2><p class="muted city-modal-intro">先选省份，再选城市／地区。已收录的城市会标记并禁用，不包含区县。</p><form class="city-create-form" data-city-form="create"><div class="city-select-columns"><section><label for="cityProvinceQuery">查找省份</label><input id="cityProvinceQuery" data-draft-ignore data-city-filter="province" type="search" maxlength="40" placeholder="例如：河南"><label for="cityProvince">省份</label><select id="cityProvince" name="province_code" size="9" required aria-label="选择省份"></select></section><section><label for="cityRegionQuery">查找城市／地区</label><input id="cityRegionQuery" data-draft-ignore data-city-filter="region" type="search" maxlength="50" placeholder="例如：洛阳"><label for="cityRegion">城市／地区</label><select id="cityRegion" name="region_code" size="9" required aria-label="选择城市／地区"></select></section></div><p class="city-selection" data-city-selection>请选择一个省份和城市。</p><p class="form-help">目录截至 '+esc(catalog.version)+'；直辖市和港澳直接对应，不展开区县。台湾省源目录暂未提供市级条目。</p><p class="form-error" role="alert"></p><div class="button-row"><button type="submit" class="button" disabled>确认新增</button><button type="button" class="button secondary" data-close="featureDialog">取消</button></div></form>');
  renderCityProvinces($('#featureDialog form'));
 }catch(error){if(nonce===cityUi.nonce&&$('#featureDialog [data-city-loading]'))$('#featureBody').innerHTML='<h2>目录暂时无法读取</h2><p class="form-error" role="alert">'+esc(error.message)+'</p><button class="button" data-city="create">重试</button>';}
}
function renderCityProvinces(form){
 const select=form.elements.province_code,old=select.value,q=form.querySelector('[data-city-filter=province]').value.trim();
 const rows=cityUi.catalog.provinces.filter(p=>!q||p.name.includes(q));
 select.innerHTML='<option value="">请选择省份</option>'+rows.map(p=>'<option value="'+p.code+'" '+(!p.cities.length?'disabled':'')+'>'+esc(p.name)+(p.cities.length?'':' · 暂无市级条目')+'</option>').join('');
 select.value=rows.some(p=>p.code===old)?old:'';renderCityRegions(form,true);
}
function renderCityRegions(form,preserve=false){
 const province=cityUi.catalog.provinces.find(p=>p.code===form.elements.province_code.value),select=form.elements.region_code;
 const old=preserve?select.value:'',q=form.querySelector('[data-city-filter=region]').value.trim();
 const rows=(province?.cities||[]).filter(c=>!q||c.name.includes(q));
 select.innerHTML='<option value="">'+(province?'请选择城市／地区':'请先选择省份')+'</option>'+rows.map(c=>'<option value="'+c.code+'" '+(c.existing?'disabled':'')+'>'+esc(c.name)+(c.existing?' · 已收录'+(Number(c.existing_published)?'':'（待完善）'):'')+'</option>').join('');
 select.value=rows.some(c=>c.code===old&&!c.existing)?old:'';updateCitySelection(form);
}
function updateCitySelection(form){
 const p=cityUi.catalog.provinces.find(p=>p.code===form.elements.province_code.value),c=p?.cities.find(c=>c.code===form.elements.region_code.value);
 form.querySelector('[data-city-selection]').textContent=c?'即将新增：'+p.name+' · '+c.name:'请选择一个省份和城市。';
 form.querySelector('[type=submit]').disabled=!c||c.existing||cityUi.busy;
}
async function openCityEditor(id){
 const user=state.user?.id,nonce=++cityUi.nonce,version=state.renderId;
 const rows=await api('/api/admin/cities');
 if(nonce!==cityUi.nonce||user!==state.user?.id||version!==state.renderId)return;
 state.adminCities=rows;const c=rows.find(c=>Number(c.id)===Number(id));if(!c)throw new Error('这座城市已不存在，请重新载入');
 featureModal('<h2>'+esc(c.name)+'目的地资料</h2><p class="muted small-text">'+esc(c.province+' · '+c.name)+' · '+(Number(c.published)?'已展示':'待完善，前台暂不可见')+'</p><form class="form-stack" data-city-form="edit" data-id="'+c.id+'" data-revision="'+c.revision+'"><input type="hidden" name="image" value="'+esc(c.image||'')+'"><label>城市封面<input type="file" accept="image/jpeg,image/png" data-city-upload></label><div class="city-cover-preview">'+(c.image?'<img src="'+esc(c.image)+'" alt="城市封面预览">':'<span>上传这座城市的实景照片</span>')+'</div><p class="form-help">JPG 或 PNG，最多5 MB。请选择有权使用、且对应这座城市的照片。</p>'+field('tagline','目的地短句',c.tagline)+field('description','城市介绍',c.description,true)+field('visit_advice','游玩节奏',c.visit_advice,true)+field('travel_advice','出行建议',c.travel_advice,true)+field('food','当地食味',c.food,true)+field('food_source','食味资料链接（可选）',c.food_source)+'<p class="form-error" role="alert"></p><div class="button-row"><button class="button" type="submit">保存资料</button><button class="button secondary" type="button" data-close="featureDialog">取消</button></div></form>');
}
async function cityManagementAction(button){
 const action=button.dataset.city;if(!action)return false;
 if(cityUi.busy)return true;
 if(action==='create'){await openCityCreate();return true;}
 if(action==='edit'){await openCityEditor(Number(button.dataset.id));return true;}
 if(action==='add-spot'){navigate('/admin?section=spots&city_id='+Number(button.dataset.id));return true;}
 if(action==='publish'){
  if(!await confirmAction('城市和已发布的相关内容将进入前台，确认资料已经准备好了吗？','展示这座城市？'))return true;
  cityUi.busy=true;button.disabled=true;
  try{await api('/api/admin/cities/'+button.dataset.id+'/publication',{method:'PUT',body:{published:true,revision:Number(button.dataset.revision)}});await refreshCityLists();await render();toast('目的地已展示');}
  catch(error){if(error.status===409)await render();throw error;}
  finally{cityUi.busy=false;button.disabled=false;}
 }
 return true;
}
async function cityManagementChange(target){
 const form=target.closest('[data-city-form]');if(!form)return;
 if(form.dataset.cityForm==='create'){
  if(target.dataset.cityFilter==='province')renderCityProvinces(form);
  else if(target.dataset.cityFilter==='region')renderCityRegions(form,true);
  else if(target.name==='province_code'){form.querySelector('[data-city-filter=region]').value='';renderCityRegions(form);}
  else if(target.name==='region_code')updateCitySelection(form);
 }
 if(target.hasAttribute('data-city-upload')&&target.files?.[0]){
  const file=target.files[0],user=state.user?.id,submit=form.querySelector('[type=submit]');form.dataset.uploads='1';submit.disabled=true;target.disabled=true;
  try{if(file.size>5*1024*1024)throw new Error('请选择5 MB以内的图片');const body=new FormData();body.append('file',file);const result=await api('/api/admin/uploads',{method:'POST',body});
   if(!form.isConnected||!$('#featureDialog').open||state.user?.id!==user)return;
   form.elements.image.value=result.url;form.querySelector('.city-cover-preview').innerHTML='<img src="'+esc(result.url)+'" alt="城市封面预览">';toast('城市封面已上传');
  }catch(error){if(form.isConnected)form.querySelector('.form-error').textContent=error.message;}
  finally{form.dataset.uploads='0';submit.disabled=false;target.disabled=false;target.value='';}
 }
}
async function submitCityManagementForm(form,event){
 const kind=form.dataset.cityForm;if(!kind)return false;event.preventDefault();
 if(cityUi.busy||Number(form.dataset.uploads||0)>0)return true;
 const submit=form.querySelector('[type=submit]'),user=state.user?.id;cityUi.busy=true;submit.disabled=true;form.querySelector('.form-error').textContent='';
 try{
  const body=Object.fromEntries(new FormData(form));let out;
  if(kind==='create')out=await api('/api/admin/cities',{method:'POST',body:{province_code:body.province_code,region_code:body.region_code}});
  else out=await api('/api/admin/cities/'+form.dataset.id,{method:'PUT',body:{...body,revision:Number(form.dataset.revision)}});
  if(!form.isConnected||!$('#featureDialog').open||state.user?.id!==user)return true;
  $('#featureDialog').close();await refreshCityLists();await render();toast(out.message);
  if(kind==='create')await openCityEditor(out.id);
 }catch(error){if(form.isConnected)form.querySelector('.form-error').textContent=error.message;}
 finally{cityUi.busy=false;submit.disabled=false;}
 return true;
}
