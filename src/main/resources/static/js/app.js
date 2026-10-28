'use strict';
const state={user:null,csrf:'',cities:[],favorites:[],favoriteKeys:new Set(),authMode:'login',renderId:0,adminRows:[],adminSpots:[],adminCities:[],drafts:new Map()};
const $=s=>document.querySelector(s);
const esc=v=>String(v==null?'':v).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const icons={
 route:'<circle cx="5" cy="5" r="2"/><circle cx="19" cy="19" r="2"/><path d="M7 5h8a4 4 0 0 1 0 8H9a3 3 0 0 0 0 6h8"/>',
 search:'<circle cx="10.8" cy="10.8" r="6.8"/><path d="m16 16 4.5 4.5"/>',
 heart:'<path d="M20.7 4.8c-2-2-5.1-2-7.1 0L12 6.4l-1.6-1.6a5 5 0 0 0-7.1 7.1L12 20.5l8.7-8.6a5 5 0 0 0 0-7.1Z"/>',
 pin:'<path d="M19 10c0 5-7 11-7 11S5 15 5 10a7 7 0 1 1 14 0Z"/><circle cx="12" cy="10" r="2.3"/>',
 book:'<path d="M4 4h6l2 2 2-2h6v15h-6l-2 2-2-2H4Z"/><path d="M12 6v15"/>',
 arrow:'<path d="M4 12h15m-6-6 6 6-6 6"/>',
 chevron:'<path d="m9 5 7 7-7 7"/>',
 comment:'<path d="M21 11.5a9 9 0 0 1-9 8.5 10 10 0 0 1-4-.8L3 21l1.5-5A8 8 0 0 1 3 11.5a9 9 0 0 1 18 0Z"/>',
 lock:'<rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3m-4 5v2"/>',
 users:'<circle cx="9" cy="8" r="3"/><path d="M3 21v-3a6 6 0 0 1 12 0v3m1-16a3 3 0 0 1 0 6m3 10v-3a6 6 0 0 0-2-4"/>',
 plus:'<path d="M12 4v16M4 12h16"/>',
 logout:'<path d="M9 4H4v16h5m6-13 5 5-5 5m-7-5h12"/>'
};
function icon(name,size=20){return '<svg width="'+size+'" height="'+size+'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'+(icons[name]||icons.pin)+'</svg>';}
function date(v){return new Date(v).toLocaleString('zh-CN',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false});}
function toast(message,error=false){const el=$('#toast');el.textContent=message;el.className='toast show'+(error?' error':'');clearTimeout(toast.timer);toast.timer=setTimeout(()=>el.classList.remove('show'),3300);}
function requestFailure(message,status,code,requestId){
 const id=/^[a-f0-9-]{36}$/.test(requestId||'')?requestId:null;
 const error=new Error(message+(status>=500&&id?'（问题编号：'+id+'）':''));error.status=status;error.code=code;error.requestId=id;return error;
}
async function api(url,options={}){
 const {timeoutMs,signal,...requestOptions}=options,controller=new AbortController();let timedOut=false;
 const duration=Number(timeoutMs)>0?Number(timeoutMs):(options.body instanceof FormData?60000:20000);
 const abort=()=>controller.abort();if(signal?.aborted)abort();else signal?.addEventListener('abort',abort,{once:true});
 const timer=setTimeout(()=>{timedOut=true;controller.abort();},duration);
 const settings={credentials:'same-origin',...requestOptions,signal:controller.signal,headers:{...(options.headers||{})}};
 if(options.body && !(options.body instanceof FormData)){settings.headers['Content-Type']='application/json';settings.body=JSON.stringify(options.body);}
 if(options.method && !['GET','HEAD'].includes(options.method))settings.headers['X-CSRF-Token']=state.csrf;
 let response;
 try{
  response=await fetch(url,settings);
  let data;
  try{data=response.status===204?{}:await response.json();}catch(error){
   if(controller.signal.aborted)throw error;
   throw requestFailure(response.ok?'网站返回了无法识别的内容，请重新加载后重试':'服务暂时无法完成请求，请稍后重试',response.ok?502:response.status,'INVALID_RESPONSE',response.headers?.get('X-Request-ID'));
  }
  if(!response.ok){
   if(response.status===401 && state.user && url!=='/api/auth/login')await recoverExpiredSession();
   if(response.status===403 && String(data?.message||'').includes('页面状态'))await recoverExpiredSession();
   const failure=requestFailure(data?.message||'操作未完成，请稍后重试',response.status,data?.code||'REQUEST_FAILED',data?.request_id||response.headers?.get('X-Request-ID'));
   if(failure.code==='LOGIN_COOLDOWN'){failure.retryAfterSeconds=Number(data?.retry_after_seconds);failure.cooldownScope=data?.cooldown_scope;}
   throw failure;
  }
  return data;
 }catch(error){
  if(error.code&&error.status)throw error;
  const id=response?.headers?.get('X-Request-ID');
  if(timedOut)throw requestFailure('请求等待时间过长，请先刷新确认操作结果，再决定是否重试',0,'REQUEST_TIMEOUT',id);
  if(controller.signal.aborted){const cancelled=requestFailure('请求已取消',0,'REQUEST_CANCELLED',id);cancelled.name='AbortError';throw cancelled;}
  throw requestFailure('网络连接失败，请确认网站仍在运行，然后重新加载',0,'NETWORK_ERROR',id);
 }finally{clearTimeout(timer);signal?.removeEventListener('abort',abort);}
}
async function recoverExpiredSession(){
 const previous=state.user?.id;await refreshSession();
 if(previous&&Number(previous)!==Number(state.user?.id||0)){
  state.favorites=[];state.favoriteKeys.clear();
  featureState.trip=null;featureState.pending=null;plannerDepthState.trip=null;plannerDepthState.schedule=null;plannerDepthState.undo=null;plannerDepthState.editor=null;
  budgetUi.data=null;mealUi.trip=null;mealUi.data=null;mealUi.editor=null;expUi.trip=null;expUi.tools=null;tripVersionsUi.context=null;
  if(typeof constraintUi!=='undefined'){constraintUi.context=null;constraintUi.save=null;}
  document.querySelectorAll('dialog[open]').forEach(d=>d.close());await render();toast(state.user?'账号已切换，已重新加载当前页面':'登录已失效，请重新登录后继续操作',true);
 }
}
function acceptSession(info){
 const previous=state.user?.id??null,next=info.user?.id??null;
 if(previous!==next){
  if(typeof clearTripPrint==='function')clearTripPrint();
  try{if(typeof window!=='undefined')window.localStorage.setItem('roam-session-change',JSON.stringify({uid:next,nonce:String(Date.now())+Math.random()}));}catch{}
  state.drafts.clear();
  if(previous!==null){
   state.favorites=[];state.favoriteKeys.clear();state.adminRows=[];state.adminSpots=[];state.adminCities=[];cityUi.catalog=null;cityUi.nonce++;contributionUi.context=null;contributionUi.pending=null;
   featureState.trip=null;featureState.sourceSpots=[];featureState.pending=null;
   plannerDepthState.trip=null;plannerDepthState.schedule=null;plannerDepthState.undo=null;plannerDepthState.editor=null;
   budgetUi.data=null;mealUi.trip=null;mealUi.data=null;mealUi.editor=null;
   expUi.trip=null;expUi.tools=null;tripVersionsUi.context=null;constraintUi.context=null;constraintUi.save=null;
   document.querySelectorAll('dialog[open]').forEach(d=>d.close());
  }
 }
 state.user=info.user;state.csrf=info.csrf;
}
async function refreshSession(){const info=await api('/api/session');acceptSession(info);updateAccount();}
async function loadFavorites(){
 if(!state.user){state.favorites=[];state.favoriteKeys.clear();return;}
 state.favorites=await api('/api/me/favorites');state.favoriteKeys=new Set(state.favorites.map(x=>x.type+':'+x.id));
}
function updateAccount(){
 const el=$('#accountActions');
 if(!state.user){el.innerHTML='<button class="button small" data-auth="login">登录 / 注册</button>';return;}
 const u=state.user;
 el.innerHTML='<details class="account-menu"><summary><span class="avatar">'+esc(u.username.slice(0,1).toUpperCase())+'</span><span class="username">'+esc(u.username)+'</span></summary><div class="account-dropdown"><a href="/trips">我的行程</a><a href="/me?tab=favorites">我的收藏</a><a href="/me?tab=comments">我的评论</a><a href="/me?tab=food">美食收藏</a><a href="/me?tab=posts">我的帖子</a><a href="/me?tab=contributions">我的投稿</a><a href="/me?tab=history">最近浏览</a><a href="/me?tab=notifications">消息中心</a><a href="/me?tab=routes">公开路线</a><a href="/me?tab=memories">旅行足迹</a><a href="/me?tab=route-favorites">路线收藏</a><a href="/feedback">意见反馈</a><a href="/me?tab=password">修改密码</a>'+(u.role==='ADMIN'?'<a href="/admin">管理后台</a>':'')+'<button data-action="logout">退出登录</button></div></details>';
}
let routeIndex=Number(history.state?.roamIndex||0),routeUrl=location.href,restoringRoute=false;
history.replaceState({...history.state,roamIndex:routeIndex},'',location.href);
history.scrollRestoration='manual';
const routeScroll=new Map();
function rememberRouteScroll(){routeScroll.set(routeIndex,window.scrollY);if(routeScroll.size>80)routeScroll.delete(routeScroll.keys().next().value);}
function navigate(url,scroll=true){
 const proceed=()=>{rememberRouteScroll();history.pushState({roamIndex:++routeIndex},'',url);routeUrl=location.href;render({scroll:scroll?0:undefined});};
 if(formSafety.hasDirtyDialogs())void formSafety.allowLeave().then(ok=>{if(ok)proceed();});else proceed();
}
function cityOptions(selected=0,all=true,includeDraft=false){const cities=includeDraft?(state.adminCities?.length?state.adminCities:state.cities):state.cities;return (all?'<option value="0">全部地区</option>':'')+cities.map(c=>'<option value="'+c.id+'" '+(Number(selected)===Number(c.id)?'selected':'')+'>'+esc(c.province+' · '+c.name+(includeDraft&&!Number(c.published)?'（待完善）':''))+'</option>').join('');}
function sectionHeading(title,subtitle,link,label='查看全部'){return '<div class="section-heading"><div><h2>'+esc(title)+'</h2><p>'+esc(subtitle)+'</p></div>'+(link?'<a class="text-link" href="'+link+'">'+esc(label)+icon('arrow',18)+'</a>':'')+'</div>';}
function favoriteButton(type,id,full=false){
 const active=state.favoriteKeys.has(type+':'+id);
 return '<button class="favorite-button'+(active?' active':'')+'" data-fav="'+type+'" data-id="'+id+'" aria-label="'+(active?'取消收藏':'加入收藏')+'" aria-pressed="'+active+'">'+icon('heart',19)+(full?'<span>'+(active?'已收藏':'收藏')+'</span>':'')+'</button>';
}
function card(item,type){const live=Number(item.published)!==0,url='/'+type+'/'+item.id,title=item.search?searchSegments(item.search.title,item.title):esc(item.title);return '<article class="content-card"><div class="card-photo">'+(live?'<a href="'+url+'" class="card-image-link">':'<span class="card-image-link">')+'<img src="'+esc(item.image)+'" alt="'+esc(item.title)+'" loading="lazy">'+(live?'</a>':'</span>')+favoriteButton(type,item.id)+(String(item.image).includes('nanyang-medical')?'<span class="status-badge archive-badge">资料照片</span>':'')+(live?'':'<span class="status-badge off">已下架</span>')+'</div><div class="card-info"><div class="card-location">'+icon('pin',13)+(item.search?searchSegments(item.search.location):esc(item.city_name||''))+'</div><h3>'+(live?'<a href="'+url+'">'+title+'</a>':title)+'</h3>'+searchEvidence(item)+'<p class="'+(item.search?'search-excerpt':'')+'">'+(item.search?searchSegments(item.search.snippet,item.intro):esc(item.intro))+'</p>'+contentMeta(item)+(type==='spot'&&live?compareButton(item.id):'')+'</div></article>';}
function grid(items,type){return '<div class="content-grid">'+items.map(x=>card(x,type||x.type)).join('')+'</div>';}
function destinationTile(city){return '<a class="destination-tile" href="/destination/'+esc(city.slug)+'"><img src="'+esc(city.image)+'" alt="'+esc(city.name)+'" loading="lazy"><div class="destination-label"><small>'+esc(city.province)+'</small><h3>'+esc(city.name)+'</h3><p>'+esc(city.tagline)+'</p></div></a>';}
function topicCard(title,description,image,q){return '<a class="topic-card" href="/search?type=spot&theme='+encodeURIComponent({'湖':'山水风景','街':'街巷生活','寺':'人文历史'}[q]||q)+'"><img src="'+image+'" alt="" loading="lazy"><div><h3>'+title+'</h3><p>'+description+'</p><span>发现相关景点 '+icon('arrow',16)+'</span></div></a>';}
function empty(title,text,link,label){return '<div class="empty">'+icon('book',38)+'<h3>'+esc(title)+'</h3><p>'+esc(text)+'</p>'+(link?'<a class="button secondary small" href="'+link+'">'+esc(label)+'</a>':'')+'</div>';}
function crumb(items){return '<nav class="breadcrumb" aria-label="当前位置"><a href="/">首页</a>'+items.map(x=>icon('chevron',12)+(x.url?'<a href="'+x.url+'">'+esc(x.label)+'</a>':'<span>'+esc(x.label)+'</span>')).join('')+'</nav>';}
function photoNote(item){return String(item.image).includes('nanyang-medical')?'<p class="photo-note">医圣祠资料照片，摄于 2005 年。当前参观场所面貌请查看官方资料。</p>':'';}
function articlePhoto(item){return '<figure class="article-photo"><a href="/spot/'+item.id+'"><img src="'+esc(item.image)+'" alt="'+esc(item.title)+'" loading="lazy"></a><figcaption>'+esc(item.city_name)+' · '+esc(item.title)+'，点击图片了解这个地方。</figcaption></figure>';}
function sourceNote(item){return '<div class="source-note">玩法与行程为编辑建议，出发前请核对最新开放、交通和天气信息。'+(item.source_url?' <a href="'+esc(item.source_url)+'" target="_blank" rel="noopener">查看官方或机构资料</a>':'')+' <a href="/feedback?from='+encodeURIComponent(location.pathname)+'">反馈内容问题</a></div>';}
async function home(){
 const [spots,guides,foodPreview]=await Promise.all([api('/api/search?type=spot&size=9'),api('/api/search?type=guide&size=24'),homeFoodPreview()]);
 const feature=guides.items[0];
 return '<div class="home-canvas"><div class="home-map-ornament" aria-hidden="true"><img src="/assets/home-map-ornament.svg" alt=""></div><section class="home-hero"><img src="/assets/nanyang-hero.jpg" alt="南阳卧龙岗武侯祠入口" fetchpriority="high"><div class="hero-content"><h1>下一站，<br>去哪里？</h1><p>从一座城、一条街开始，<br>发现值得停留的地方。</p><form class="hero-search" data-search>'+icon('search',21)+'<input name="q" aria-label="搜索城市、景点或攻略" placeholder="想去的城市、景点，或旅行灵感"><button type="submit">去探索</button></form></div><a class="hero-caption" href="/destination/nanyang">'+icon('pin',21)+'<div>河南 · 南阳<span>在卧龙岗，读一段城市历史</span></div></a></section>'+
 '<div class="container"><section class="section home-destinations">'+sectionHeading('从一个目的地开始','山水、街巷与人文，找到属于你的旅行节奏。','/destinations','探索目的地')+'<div class="dest-grid">'+state.cities.map(destinationTile).join('')+'</div></section>'+tripCallout()+
 '<section class="section home-themes">'+sectionHeading('这次，想怎样出发','从一种喜欢的旅行方式，找到想去的地方。')+'<div class="theme-grid">'+topicCard('沿着湖岸走走','给山水、树影与一段慢行留出时间。','/assets/hangzhou-lake.jpg','湖')+topicCard('走进街巷日常','在老街、小店和建筑之间认识城市。','/assets/chengdu.jpg','街')+topicCard('读一段地方历史','从寺院与古建，走近沿途的人文。','/assets/quanzhou.jpg','寺')+'</div></section>'+
 '<section class="section home-spots">'+sectionHeading('沿途，值得停留','看见风景，也读懂每一个地方。','/search?type=spot','发现更多景点')+grid(spots.items,'spot')+'</section>'+
 '<section class="section home-last">'+sectionHeading('出发前，读一份攻略','有用的安排，也有值得慢读的地方故事。','/guides','全部攻略')+
 (feature?'<article class="guide-feature"><img src="'+esc(feature.image)+'" alt="'+esc(feature.title)+'" loading="lazy"><div class="feature-copy"><div class="card-location">'+icon('pin',14)+esc(feature.city_name)+'</div><h2>'+esc(feature.title)+'</h2><p>'+esc(feature.intro)+'</p><a class="button secondary" href="/guide/'+feature.id+'">阅读这份攻略 '+icon('arrow',17)+'</a></div></article>':'')+'<div style="margin-top:30px">'+grid(guides.items.slice(-3),'guide')+'</div></section>'+foodPreview+homeCommunityIntro()+'</div></div>';
}
async function destinations(slug){
 if(!slug)return destinationExplorerPage();
 return cityStoryPage(slug);
}
async function searchPage(onlyGuides=false){
 const p=new URLSearchParams(location.search),q=p.get('q')||'',city=p.get('city')||'0',type=onlyGuides?'guide':(p.get('type')==='guide'?'guide':'spot'),page=Math.max(1,Number(p.get('page')||1));
 const theme=p.get('theme')||'',duration=p.get('duration')||'',sort=p.get('sort')==='editor'?'editor':'relevance';
 const base='q='+encodeURIComponent(q)+'&city='+encodeURIComponent(city)+'&theme='+encodeURIComponent(theme)+'&duration='+encodeURIComponent(duration)+'&sort='+sort;
 const [spots,guides]=await Promise.all([api('/api/search?type=spot&'+base+'&page='+page),api('/api/search?type=guide&'+base+'&page='+page)]);
 const result=type==='spot'?spots:guides,root=onlyGuides?'/guides':'/search';
 const link=(t,n=1)=>root+'?'+base+'&type='+t+'&page='+n;
 let pagination='';if(result.total>result.size){const pages=Math.ceil(result.total/result.size);pagination='<nav class="pagination" aria-label="结果分页">'+Array.from({length:pages},(_,i)=>'<a class="'+(i+1===page?'active':'')+'" href="'+link(type,i+1)+'" aria-label="第 '+(i+1)+' 页">'+(i+1)+'</a>').join('')+'</nav>';}
 return '<div class="explore-canvas"><div class="container explore-page '+(onlyGuides?'guides-index':'search-index')+'"><div class="page-top"><div><h1>'+(onlyGuides?'出发前的实用攻略':'找一个值得去的地方')+'</h1><p>'+(onlyGuides?'从游玩安排到交通与细节，带着准备出发。':'从标题到正文，找到你想了解的目的地与旅行细节。')+'</p></div>'+(onlyGuides?'<button class="button secondary small" data-contribution-start="guide" data-city-id="'+(Number(city)>0?Number(city):'')+'">'+icon('plus',15)+'分享攻略</button>':'')+'</div><form class="filters search-filters" data-search data-target="'+root+'"><input name="q" value="'+esc(q)+'" placeholder="如：南阳 汉画，或交通、慢行" maxlength="100" aria-label="搜索词"><select name="city" aria-label="选择地区">'+cityOptions(city)+'</select><select name="theme" aria-label="选择旅行主题">'+themeOptions(theme)+'</select><select name="duration" aria-label="选择游玩时长">'+durationOptions(duration)+'</select><select name="sort" aria-label="搜索结果排序"><option value="relevance" '+(sort==='relevance'?'selected':'')+'>相关度优先</option><option value="editor" '+(sort==='editor'?'selected':'')+'>收录顺序</option></select><input type="hidden" name="type" value="'+type+'"><button class="button" type="submit">'+icon('search',17)+'搜索</button><a class="text-link" href="'+root+'">清空</a></form><p class="search-help">可搜索景点介绍、游览提示和攻略全文。多个关键词用空格隔开，结果须同时包含；中文短语按连续文字匹配。</p>'+
 (!onlyGuides?'<div class="tabs"><a href="'+link('spot')+'" class="'+(type==='spot'?'active':'')+'">景点<span class="count">'+spots.total+'</span></a><a href="'+link('guide')+'" class="'+(type==='guide'?'active':'')+'">攻略<span class="count">'+guides.total+'</span></a></div>':'')+
 '<div class="result-caption"><span>'+(q?'“'+esc(q)+'” 的搜索结果':'全部'+(type==='spot'?'景点':'攻略'))+'，共 '+result.total+' 条</span><span>'+(q.trim()&&sort==='relevance'?'按相关度排序':'按编辑收录顺序展示')+'</span></div>'+
 (result.items.length?(q.trim()?'<div class="search-result-list">'+result.items.map(x=>card(x,type)).join('')+'</div>':grid(result.items,type)):empty('暂时没有找到相关内容','试着减少关键词，或清空地区、主题和时长筛选。',root,'清空条件'))+pagination+'</div></div>';
}
function miniRelated(item,type){return '<a class="mini-related" href="/'+type+'/'+item.id+'"><img src="'+esc(item.image)+'" alt="'+esc(item.title)+'" loading="lazy"><div><h3>'+esc(item.title)+'</h3><p>'+esc(item.city_name||'')+'</p></div></a>';}
async function spotDetail(id){
 const s=await api('/api/spots/'+id);await recordHistory('spot',id);const connections=await renderSpotConnections(s);
 const sections=[['关于这里',s.description],['值得留意',s.highlights],['怎样抵达',s.transportation],['游览提示',s.tips]].filter(x=>x[1]);
 return '<div class="explore-canvas"><div class="container explore-page reading-page spot-reading">'+crumb([{label:'目的地',url:'/destinations'},{label:s.city_name,url:'/destination/'+s.city_slug},{label:s.title}])+spotGallery(s)+'<div class="detail-heading"><div><a class="location-label" href="/destination/'+s.city_slug+'">'+icon('pin',15)+esc(s.province+' · '+s.city_name)+'</a><h1>'+esc(s.title)+'</h1><p>'+esc(s.intro)+'</p>'+contentMeta(s)+'</div><div class="detail-actions"><a class="button secondary small" href="#spot-map">'+icon('pin',16)+'查看地图</a><button class="button small" data-feature="add-to-trip" data-id="'+s.id+'">'+icon('plus',16)+'加入行程</button>'+favoriteButton('spot',s.id,true)+compareButton(s.id,true)+'<button class="text-button contribution-detail-link" data-contribution-update="spot" data-target-id="'+s.id+'">纠错 / 补充资料</button></div></div><div class="detail-layout"><article class="article">'+sections.map((x,i)=>'<section id="section-'+i+'"><h2>'+esc(x[0])+'</h2><p>'+esc(x[1])+'</p></section>').join('')+spotMapPanel(s)+sourceNote(s)+
 '<section class="related-section"><h2>怎样把这里安排进旅程</h2>'+ (s.guides.length?s.guides.map(g=>miniRelated(g,'guide')).join(''):'<p class="muted">相关攻略正在整理中。</p>')+'</section>'+connections+'</article><aside class="article-sidebar"><h3>这页内容</h3>'+sections.map((x,i)=>'<a href="#section-'+i+'">'+esc(x[0])+'</a>').join('')+'<a href="#spot-map">地图与到达</a><a href="/destination/'+s.city_slug+'">继续探索'+esc(s.city_name)+'</a></aside></div></div></div>';
}
function commentRows(comments){return comments.map(c=>'<article class="comment"><span class="avatar">'+esc(c.username.slice(0,1))+'</span><div class="comment-body"><div class="comment-meta"><strong>'+esc(c.username)+'</strong><time datetime="'+esc(c.created_at)+'">'+date(c.created_at)+(c.updated_at?' · 已编辑':'')+'</time>'+ (state.user&&Number(state.user.id)===Number(c.user_id)?'<button class="delete-link comment-edit" data-feature="edit-comment" data-id="'+c.id+'" data-body="'+esc(c.body)+'">修改</button>':'')+ (state.user && (Number(state.user.id)===Number(c.user_id)||state.user.role==='ADMIN')?'<button class="delete-link" data-delete-comment="'+c.id+'">删除</button>':'')+'</div><p class="comment-text">'+esc(c.body)+'</p>'+commentReactionButton(c)+'</div></article>').join('');}
async function guideDetail(id){
 const [g,comments]=await Promise.all([api('/api/guides/'+id),api('/api/guides/'+id+'/comments')]);
 await recordHistory('guide',id);const sections=Array.isArray(g.sections)?g.sections:[];const illustration=g.spots.find(s=>s.image!==g.image && !String(s.image).includes('medical'));
 return '<div class="explore-canvas"><div class="container explore-page reading-page guide-reading">'+crumb([{label:'旅行攻略',url:'/guides'},{label:g.city_name,url:'/destination/'+g.city_slug},{label:'阅读攻略'}])+'<img class="detail-hero" src="'+esc(g.image)+'" alt="'+esc(g.title)+'"><div class="detail-heading"><div><a class="location-label" href="/destination/'+g.city_slug+'">'+icon('pin',15)+esc(g.province+' · '+g.city_name)+'</a><h1>'+esc(g.title)+'</h1><p>'+esc(g.subtitle)+'</p>'+contentMeta(g)+'</div><div class="detail-actions"><button class="button small" data-feature="guide-trip" data-id="'+g.id+'">按这份攻略建行程</button>'+favoriteButton('guide',g.id,true)+'<button class="text-button contribution-detail-link" data-contribution-update="guide" data-target-id="'+g.id+'">纠错 / 补充资料</button></div></div><div class="detail-layout"><article class="article"><p class="article-intro">'+esc(g.intro)+'</p>'+sections.map((s,i)=>'<section id="section-'+i+'"><h2>'+esc(s.title)+'</h2><p>'+esc(s.body)+'</p></section>'+(i===1 && illustration?articlePhoto(illustration):'')).join('')+sourceNote(g)+
 '<section class="related-section"><h2>这份攻略提到的地方</h2>'+ (g.spots.length?g.spots.map(s=>miniRelated(s,'spot')).join(''):'<p class="muted">暂时没有关联景点。</p>')+'</section><section class="comments-section"><h2>一起聊聊这份攻略 <span class="muted small-text">'+comments.length+' 条评论</span></h2>'+
 (state.user?'<form class="comment-compose" data-comment-form="'+g.id+'"><label class="small-text muted" for="commentBody">有什么想了解或分享的？</label><textarea id="commentBody" name="body" maxlength="1000" required placeholder="写下你的想法，或给后来的人留一点建议。">'+esc(state.drafts.get(String(g.id))||'')+'</textarea><div class="compose-bottom"><span class="muted small-text">单条评论最多 1000 字</span><button class="button small" type="submit">发表评论</button></div></form>':'<div class="comment-login"><span>登录后，分享你的想法或提出问题。</span><button class="button small" data-auth="login">登录后评论</button></div>')+
 (comments.length?commentRows(comments):'<p class="muted small-text" style="margin-top:24px">还没有评论，欢迎留下第一条想法。</p>')+'</section></article><aside class="article-sidebar"><h3>阅读目录</h3>'+sections.map((s,i)=>'<a href="#section-'+i+'">'+esc(s.title)+'</a>').join('')+'<a href="/destination/'+g.city_slug+'">继续探索'+esc(g.city_name)+'</a></aside></div></div></div>';
}
function profileSidebar(admin=false,tab='favorites'){
 const items=admin?[['overview','网站概览','book'],['completeness','内容完整度','book'],['cities','目的地管理','pin'],['spots','景点管理','pin'],['guides','攻略管理','book'],['foods','美食管理','book'],['contributions','投稿审核','book'],['posts','社区帖子','comment'],['routes','公开路线','route'],['comments','评论管理','comment'],['users','用户管理','users'],['feedback','意见反馈','comment'],['audit','操作记录','book']]:[['trips','我的行程','route'],['favorites','我的收藏','heart'],['comments','我的评论','comment'],['food','美食收藏','heart'],['posts','我的帖子','comment'],['contributions','我的投稿','book'],['history','最近浏览','book'],['notifications','消息中心','comment'],['routes','公开路线','route'],['memories','旅行足迹','pin'],['route-favorites','路线收藏','heart'],['feedback','意见反馈','comment'],['password','修改密码','lock']];
 return '<aside class="side-panel"><div class="profile"><span class="avatar">'+esc(state.user.username.slice(0,1))+'</span><div>'+esc(state.user.username)+'<div class="muted small-text">'+(admin?'内容管理后台':'个人中心')+'</div></div></div><nav class="side-nav">'+items.map(x=>'<a href="'+(['trips','feedback'].includes(x[0])&&!admin?'/'+x[0]:(admin?'/admin?section=':'/me?tab=')+x[0])+'" class="'+(tab===x[0]?'active':'')+'">'+icon(x[2],18)+x[1]+'</a>').join('')+'</nav></aside>';
}
function passwordForm(forced=false){return (forced?'<div class="notice">你正在使用管理员提供的临时密码，请先设置自己的新密码。</div>':'<p class="muted small-text">修改密码后，其他设备上的登录状态会失效。</p>')+'<form class="form-stack password-card" data-password-form><label>原密码<input type="password" name="old_password" required autocomplete="current-password" maxlength="128"></label><label>新密码<input type="password" name="new_password" required minlength="8" maxlength="128" autocomplete="new-password" placeholder="至少 8 个字符"></label><label>确认新密码<input type="password" name="confirm_password" required minlength="8" maxlength="128" autocomplete="new-password"></label><p class="form-error" role="alert"></p><div><button class="button" type="submit">保存新密码</button></div></form>';}
async function mePage(){
 if(!state.user)return '<div class="container journal-page account-entry"><div class="page-top"><h1>你的旅行收藏与交流</h1><p>登录后，在这里查看收藏和自己发表的评论。</p></div><div class="empty">'+icon('heart',38)+'<h3>先登录，找回你的记录</h3><button class="button" data-auth="login">登录 / 注册</button></div></div>';
 const p=new URLSearchParams(location.search);let tab=p.get('tab')||'favorites';if(tab==='trips')return tripList();if(!state.user.must_change_password&&tab==='contributions')return contributionsPage();if(!state.user.must_change_password&&tab==='route-favorites')return routeFavoritesPage();if(!state.user.must_change_password&&tab==='notifications')return notificationsPage();if(!state.user.must_change_password&&tab==='routes')return myRoutesPage();if(!state.user.must_change_password&&tab==='memories')return memoriesPage();if(!state.user.must_change_password&&tab==='food')return myFoodFavoritesPage();if(!state.user.must_change_password&&tab==='posts')return myPostsPage();if(tab==='history'&&!state.user.must_change_password)return historyPage();
 if(state.user.must_change_password)tab='password';
 if(!['favorites','comments','password'].includes(tab))tab='favorites';
 let content='';
 if(tab==='favorites'){
  await loadFavorites();const type=p.get('type')==='guide'?'guide':'spot',items=state.favorites.filter(x=>x.type===type);
  content='<h1>我的收藏</h1><p class="muted small-text">先收藏喜欢的地方，留待下一次出发。</p><div class="tabs"><a class="'+(type==='spot'?'active':'')+'" href="/me?tab=favorites&type=spot">景点<span class="count">'+state.favorites.filter(x=>x.type==='spot').length+'</span></a><a class="'+(type==='guide'?'active':'')+'" href="/me?tab=favorites&type=guide">攻略<span class="count">'+state.favorites.filter(x=>x.type==='guide').length+'</span></a></div>'+(items.length?grid(items,type):empty('这里还没有收藏','找到感兴趣的景点或攻略时，点一下心形按钮就能留在这里。',type==='spot'?'/destinations':'/guides',type==='spot'?'探索目的地':'阅读攻略'));
 }else if(tab==='comments'){
  const rows=await api('/api/me/comments');
  content='<h1>我的评论</h1><p class="muted small-text">看看聊过的话题，也可以返回攻略继续阅读。</p>'+ (rows.length?rows.map(c=>'<article class="my-comment"><h3>'+(Number(c.published)?'<a href="/guide/'+c.guide_id+'">'+esc(c.guide_title)+'</a>':esc(c.guide_title)+' <span class="status-badge off">已下架</span>')+'</h3><span class="card-location">'+esc(c.city_name)+'</span><blockquote>'+esc(c.body)+'</blockquote><footer><time>'+date(c.created_at)+'</time><span><button class="delete-link" data-feature="edit-comment" data-id="'+c.id+'" data-body="'+esc(c.body)+'">修改</button><button class="delete-link" data-delete-comment="'+c.id+'">删除这条评论</button></span></footer></article>').join(''):empty('还没有发表过评论','阅读攻略后，可以留下你的问题或想法。','/guides','去看攻略'));
 }else content='<h1>修改密码</h1>'+passwordForm(state.user.must_change_password);
 return '<div class="container me-layout">'+profileSidebar(false,tab)+'<section class="me-main">'+content+'</section></div>';
}
async function adminPage(){
 if(!state.user)return '<div class="container"><div class="page-top"><h1>管理后台</h1><p>请使用管理员账号登录。</p></div><button class="button" data-auth="login">登录后台</button></div>';
 if(state.user.role!=='ADMIN')return '<div class="error-page"><h1>这个页面需要管理员权限</h1><p class="muted">你仍然可以浏览内容，管理自己的收藏和评论。</p><a class="button" href="/">返回首页</a></div>';
 const requestVersion=state.renderId,requestUser=state.user.id;
 const p=new URLSearchParams(location.search),section=['overview','cities','spots','guides','comments','users','feedback','foods','posts','routes','completeness','audit','contributions'].includes(p.get('section'))?p.get('section'):'overview';
 if(section==='contributions')return adminContributionsPage();
 if(section==='completeness')return adminCompletenessPage();if(section==='overview')return adminOverview();
 if(section==='audit')return adminAuditPage();
 if(section==='cities')return adminCitiesPage();
 if(section==='feedback')return adminFeedbackPage();
 if(section==='foods')return adminFoodsPage();
 if(section==='posts')return adminPostsPage();
 if(section==='routes')return adminRoutesPage();
 const [data,cities,spots]=await Promise.all([adminListPage(section),['spots','guides'].includes(section)?api('/api/admin/cities'):Promise.resolve([]),section==='guides'?api('/api/admin/spots'):Promise.resolve([])]);
 if(requestVersion!==state.renderId||requestUser!==state.user?.id)return '';
 const rows=data.items;state.adminRows=rows;state.adminCities=cities;state.adminSpots=spots;
 const targetCity=section==='spots'?state.adminCities.find(c=>Number(c.id)===Number(p.get('city_id'))):null;
 const names={spots:'景点管理',guides:'攻略管理',comments:'评论管理',users:'用户管理'};
 let heads='',body='';
 if(['spots','guides'].includes(section)){
  heads='<th>封面</th><th>内容</th><th>地区</th><th>状态</th><th>操作</th>';
  body=rows.map(x=>'<tr data-admin-row data-keyword="'+esc(x.title+' '+x.city_name)+'"><td><img class="table-image" src="'+esc(x.image)+'" alt=""></td><td><div class="table-title">'+esc(x.title)+'</div><div class="table-intro">'+esc(x.intro)+'</div></td><td>'+esc(x.city_name)+'</td><td><span class="status-badge '+(Number(x.published)?'':'off')+'">'+(Number(x.published)?'已发布':'已下架')+'</span></td><td><div class="table-actions"><button data-edit="'+section+'" data-id="'+x.id+'">编辑</button><button data-publish="'+section+'" data-id="'+x.id+'">'+(Number(x.published)?'下架':'发布')+'</button></div></td></tr>').join('');
 }else if(section==='comments'){
  heads='<th>评论与攻略</th><th>用户</th><th>时间</th><th>操作</th>';
  body=rows.map(x=>'<tr data-admin-row data-keyword="'+esc(x.body+' '+x.username+' '+x.guide_title)+'"><td><div class="table-title">'+esc(x.guide_title)+'</div><div style="max-width:420px;white-space:pre-wrap;overflow-wrap:anywhere">'+esc(x.body)+'</div></td><td>'+esc(x.username)+'</td><td class="table-date">'+date(x.created_at)+'</td><td><div class="table-actions"><button class="danger-text" data-delete-comment="'+x.id+'">删除</button></div></td></tr>').join('');
 }else{
  heads='<th>用户名</th><th>身份</th><th>状态</th><th>注册时间</th><th>操作</th>';
  body=rows.map(x=>'<tr data-admin-row data-keyword="'+esc(x.username)+'"><td>'+esc(x.username)+'</td><td>'+(x.role==='ADMIN'?'管理员':'普通用户')+'</td><td><span class="status-badge '+(x.status==='BANNED'?'off':'')+'">'+(x.status==='BANNED'?'已封禁':'正常')+'</span></td><td class="table-date">'+date(x.created_at)+'</td><td>'+(x.role==='ADMIN'?'<span class="muted small-text">个人中心修改密码</span>':'<div class="table-actions"><button data-user-status="'+x.id+'" data-status="'+(x.status==='ACTIVE'?'BANNED':'ACTIVE')+'">'+(x.status==='ACTIVE'?'封禁':'解封')+'</button><button data-reset-password="'+x.id+'">重置密码</button></div>')+'</td></tr>').join('');
 }
 return '<div class="container admin-layout">'+profileSidebar(true,section)+'<section class="admin-main"><h1>'+names[section]+'</h1>'+(targetCity?'<p class="notice">新增景点默认归属：'+esc(targetCity.province+' · '+targetCity.name)+(Number(targetCity.published)?'':'（目的地待完善，前台暂不可见）')+'</p>':'')+'<p class="muted small-text">'+(section==='users'?'封禁限制账号使用，历史评论和收藏继续保留。':section==='comments'?'评论直接公开，管理员可以删除需要处理的评论。':'在这里更新内容，保存后的变化会同步到游客页面。')+'</p>'+adminSearch(data,section,(['spots','guides'].includes(section)?'<button type="button" class="button small" data-edit="'+section+'" data-id="0">'+icon('plus',17)+'新增'+(section==='spots'?'景点':'攻略')+'</button>':'<span class="muted small-text">共 '+data.total+' 条</span>'))+'<div class="table-wrap"><table><thead><tr>'+heads+'</tr></thead><tbody>'+ (body||'<tr><td colspan="5" style="text-align:center;padding:35px">这里暂时没有记录。</td></tr>')+'</tbody></table></div>'+adminPagination(data)+'</section></div>';
}
async function render(options={}){
 const id=++state.renderId;const path=location.pathname;
 const imageDialog=document.getElementById('contributionImageDialog');if(imageDialog?.open)imageDialog.close();
 const motion=routeMotion.begin(id,location.href,options.scroll);
 if(typeof syncTripPrintRoute==='function')syncTripPrintRoute(path);
 if(typeof syncTripToolsRoute==='function')syncTripToolsRoute();
 updateActiveNavigation(path);
 try{
  let html;
  if(path==='/')html=await home();
  else if(path==='/destinations')html=await destinations();
  else if(path.startsWith('/destination/'))html=await destinations(path.split('/')[2]);
  else if(path.startsWith('/spot/'))html=await spotDetail(path.split('/')[2]);
  else if(path.startsWith('/guide/'))html=await guideDetail(path.split('/')[2]);
  else if(path==='/guides')html=await searchPage(true);
  else if(path==='/search')html=await searchPage();
  else if(path==='/me')html=await mePage();
  else if(path==='/food')html=await foodPage();
  else if(path.startsWith('/food/'))html=await foodDetail(path.split('/')[2]);
  else if(path==='/community')html=await socialPage();
  else if(path.startsWith('/community/post/'))html=await socialDetail(path.split('/')[3]);
  else if(path==='/routes')html=await routesPage();
  else if(path.startsWith('/routes/'))html=await routeDetail(path.split('/')[2]);
  else if(path==='/recommend')html=await recommendPage();
  else if(path==='/planner')html=await constraintPlannerPage();
  else if(path==='/trips')html=await tripList();
  else if(path==='/feedback')html=await feedbackPage();
  else if(path.startsWith('/trip/'))html=await tripDetail(path.split('/')[2]);
  else if(path==='/admin')html=await adminPage();
  else throw new Error('这个页面不存在');
  if(id!==state.renderId||!await routeMotion.commit(motion,html))return;bindPlannerDepth();
  Promise.resolve().then(()=>updateCompareUi()).catch(()=>{});
  Promise.resolve().then(()=>refreshExperienceBadge()).catch(()=>{});
  if(path==='/me'&&typeof contributionRouteReady==='function')contributionRouteReady();
  if(path.startsWith('/guide/'))loadCommentReactions(path.split('/')[2]);
 }catch(error){if(id!==state.renderId)return;await routeMotion.commit(motion,'<div class="error-page"><h1>'+(error.status===404?'内容暂时无法查看':'页面没有加载完成')+'</h1><p class="muted">'+esc(error.message)+'</p><div class="button-row" style="justify-content:center"><button class="button" data-action="retry">重新加载</button><a class="button secondary" href="/">返回首页</a></div></div>');}
}
const loginCooldown={pairs:new Map(),sourceUntil:0,timer:null,busy:false};
function loginNameKey(value){return value.replace(/^[\u0000-\u0020]+|[\u0000-\u0020]+$/g,'').replace(/[A-Z]/g,c=>c.toLowerCase());}
function paintLoginCooldown(){
 const form=$('#authForm'),button=$('#authSubmit'),notice=$('#authCooldown');if(!form||!button||!notice)return;
 const now=Date.now(),key=loginNameKey(form.elements.username.value);
 for(const [name,until] of loginCooldown.pairs)if(until<=now)loginCooldown.pairs.delete(name);
 const until=state.authMode==='login'?Math.max(loginCooldown.sourceUntil,loginCooldown.pairs.get(key)||0):0;
 const seconds=Math.max(0,Math.ceil((until-now)/1000));
 notice.textContent=seconds?'请等待 '+seconds+' 秒后再登录。':'';
 button.disabled=loginCooldown.busy||seconds>0;
 clearTimeout(loginCooldown.timer);if(seconds)loginCooldown.timer=setTimeout(paintLoginCooldown,Math.min(1000,until-now));
}
function authErrorIsCurrent(mode,name){return state.authMode===mode && loginNameKey($('#authForm').elements.username.value)===loginNameKey(name);}
function rememberLoginCooldown(error,name){
 const seconds=error.retryAfterSeconds;if(!Number.isInteger(seconds)||seconds<1)return;
 const until=Date.now()+seconds*1000;
 if(error.cooldownScope==='source')loginCooldown.sourceUntil=Math.max(loginCooldown.sourceUntil,until);
 else loginCooldown.pairs.set(loginNameKey(name),until);
 paintLoginCooldown();
}
function openAuth(mode='login'){
 const dialog=$('#authDialog');state.authMode=mode;$('#authError').textContent='';
 $('#authHeading').textContent=mode==='register'?'开启你的漫游':'欢迎回来';
 $('#authIntro').textContent=mode==='register'?'留下喜欢的地方，和更多人交流旅行想法。':'登录后，安排旅行、收藏美食，参与社区交流。';
 $('#confirmPasswordLabel').hidden=mode!=='register';$('#authForm [name=confirm_password]').required=mode==='register';
 $('#authForm [name=password]').autocomplete=mode==='register'?'new-password':'current-password';
 $('#authForm [name=password]').minLength=mode==='register'?8:1;
 $('#authSubmit').textContent=mode==='register'?'注册并登录':'登录';
 document.querySelectorAll('[data-auth-tab]').forEach(b=>b.setAttribute('aria-selected',b.dataset.authTab===mode?'true':'false'));
 paintLoginCooldown();
 if(!dialog.open)dialog.showModal();
}
async function confirmAction(message,title='确认操作'){
 const dialog=$('#confirmDialog');if(dialog.open)return false;
 $('#confirmTitle').textContent=title;$('#confirmText').textContent=message;dialog.showModal();
 return new Promise(resolve=>{
  let settled=false;
  const done=value=>{if(settled)return;settled=true;$('#confirmYes').onclick=null;$('#confirmNo').onclick=null;dialog.oncancel=null;dialog.onclose=null;if(dialog.open)dialog.close();resolve(value);};
  $('#confirmYes').onclick=()=>done(true);$('#confirmNo').onclick=()=>done(false);dialog.oncancel=e=>{e.preventDefault();done(false);};dialog.onclose=()=>done(false);
 });
}
async function changeFavorite(button){
 if(!state.user){openAuth();return;}
 const type=button.dataset.fav,id=button.dataset.id,key=type+':'+id,had=state.favoriteKeys.has(key);
 button.disabled=true;
 try{await api('/api/favorites/'+type+'/'+id,{method:had?'DELETE':'PUT'});if(had)state.favoriteKeys.delete(key);else state.favoriteKeys.add(key);
  document.querySelectorAll('[data-fav="'+type+'"][data-id="'+id+'"]').forEach(b=>{b.classList.toggle('active',!had);b.setAttribute('aria-pressed',String(!had));b.setAttribute('aria-label',had?'加入收藏':'取消收藏');const span=b.querySelector('span');if(span)span.textContent=had?'收藏':'已收藏';});
  toast(had?'已取消收藏':'已加入收藏');
  if(location.pathname==='/me')await render();
 }catch(error){toast(error.message,true);if(error.status===401)openAuth();}finally{button.disabled=false;}
}
function editorSection(s={},index=0){return '<div class="section-editor" data-section><div class="section-toolbar"><span>攻略小节</span><button type="button" class="delete-link" data-remove-section>移除</button></div><input aria-label="小节标题" data-section-title required maxlength="100" placeholder="例如：怎样安排游玩" value="'+esc(s.title||'')+'"><textarea aria-label="小节正文" data-section-body required maxlength="10000" placeholder="填写具体内容">'+esc(s.body||'')+'</textarea></div>';}
function field(name,label,value='',multiline=false,required=false){return '<label>'+esc(label)+(multiline?'<textarea':'<input')+' name="'+name+'" '+(required?'required':'')+(multiline?' rows="4">':' value="'+esc(value)+'">')+(multiline?esc(value)+'</textarea>':'')+'</label>';}
function openEditor(kind,id,overrideItem=null){
 const defaultCity=state.adminCities?.find(c=>Number(c.id)===Number(new URLSearchParams(location.search).get('city_id')))||state.adminCities?.[0]||state.cities[0];
 const item=id?(overrideItem||state.adminRows.find(x=>Number(x.id)===Number(id))):{city_id:defaultCity.id,image:defaultCity.image||'/assets/compass.svg',published:0,sections:[{title:'游玩安排',body:''},{title:'交通与用餐',body:''},{title:'出行提示',body:''}],spot_ids:[]};
 if(!item)return;
 const isGuide=kind==='guides';
 const presets=allPhotoPresets();
 $('#editBody').innerHTML='<h2>'+(id?'编辑':'新增')+(isGuide?'攻略':'景点')+'</h2><p class="muted small-text">带有必填要求的内容保存完整后，再选择是否发布。</p><form id="contentForm" class="form-stack" data-kind="'+kind+'" data-id="'+id+'" data-revision="'+(item.revision||0)+'">'+field('title','标题',item.title,false,true)+'<div class="row"><label>所属地区<select name="city_id">'+cityOptions(item.city_id,false,true)+'</select></label><label>展示状态<select name="published"><option value="0" '+(!Number(item.published)?'selected':'')+'>未发布 / 已下架</option><option value="1" '+(Number(item.published)?'selected':'')+'>发布到前台</option></select></label></div>'+field('intro','简介',item.intro,true,true)+
 '<label>封面图片<select data-image-preset><option value="">选择预置图片，或在下方上传</option>'+presets.map(x=>'<option value="/assets/'+x[0]+'" '+(item.image==='/assets/'+x[0]?'selected':'')+'>'+x[1]+'</option>').join('')+'</select></label><input type="hidden" name="image" value="'+esc(item.image)+'"><div class="upload-row"><img class="image-preview" src="'+esc(item.image)+'" alt="封面预览"><div><label>上传自己的图片<input type="file" data-upload accept="image/jpeg,image/png"></label><span class="form-help">JPG 或 PNG，最多 5 MB，上传后会自动缩放。</span></div></div>'+
 (isGuide?field('subtitle','副标题',item.subtitle)+'<fieldset class="editor-fieldset"><legend>关联景点</legend><div class="spot-checks">'+state.adminSpots.map(s=>'<label data-spot-city="'+s.city_id+'"><input name="spot_ids" type="checkbox" value="'+s.id+'" '+((item.spot_ids||[]).map(Number).includes(Number(s.id))?'checked':'')+'>'+esc(s.city_name+' · '+s.title)+'</label>').join('')+'</div></fieldset><div><span class="small-text">攻略正文</span><div id="sectionEditors" style="margin-top:12px">'+(item.sections||[]).map(editorSection).join('')+'</div><button type="button" class="button secondary small" data-add-section>'+icon('plus',15)+'添加小节</button></div>':
 field('description','景点介绍',item.description,true,true)+field('highlights','值得留意',item.highlights,true)+field('transportation','交通建议',item.transportation,true)+'<label>地图检索地点（可选）<input name="map_query" maxlength="200" value="'+esc(item.map_query||'')+'" placeholder="留空时使用省份、城市和景点名"></label>'+field('tips','游览提示',item.tips,true))+
 metadataFields(item,isGuide)+field('source_url','官方或机构资料链接（可选）',item.source_url)+'<p class="form-error" role="alert"></p><div class="button-row"><button class="button" type="submit">保存内容</button><button class="button secondary" type="button" data-close="editDialog">取消</button></div></form>';
 filterEditorSpots();$('#editDialog').showModal();
}
function filterEditorSpots(){const form=$('#contentForm');if(!form)return;const city=form.elements.city_id.value;form.querySelectorAll('[data-spot-city]').forEach(l=>{const ok=l.dataset.spotCity===city;l.hidden=!ok;const input=l.querySelector('input');input.disabled=!ok;if(!ok)input.checked=false;});}
async function doAction(button){
 if(await contributionAction(button))return;
 if(await contributionAdminAction(button))return;
 if(await destinationExplorerAction(button))return;
 if(await cityManagementAction(button))return;
 if(await constraintPlannerAction(button))return;
 if(button.hasAttribute('data-theme-toggle')){const mode=window.roamTheme.toggle();toast(mode==='dark'?'已切换到黑夜模式':'已切换到白天模式');return;}
 if(await tripVersionsAction(button))return;
 if(await recommendDepthAction(button))return;
 if(await plannerDepthAction(button))return;
 if(await mealAction(button))return;
 if(await depthAction(button))return;
 if(await experienceAction(button))return;
 if(await compareAction(button))return;
 if(await budgetAction(button))return;
 if(await socialAction(button))return;
 if(await foodAction(button))return;
 if(await communityAction(button))return;
 if(await feedbackAction(button))return;
 if(await featureAction(button))return;
 if(button.dataset.fav){await changeFavorite(button);return;}
 if(button.dataset.auth){openAuth(button.dataset.auth);return;}
 if(button.dataset.authTab){openAuth(button.dataset.authTab);return;}
 if(button.dataset.close){const d=document.getElementById(button.dataset.close);if(['editDialog','featureDialog'].includes(d.id))await formSafety.requestClose(d);else d.close();return;}
 if(button.dataset.action==='retry'){await render();return;}
 if(button.dataset.action==='logout'){const info=await api('/api/auth/logout',{method:'POST'});acceptSession(info);state.favoriteKeys.clear();state.favorites=[];updateAccount();toast('已退出登录');await render();return;}
 if(button.dataset.deleteComment){
  if(!await confirmAction('删除后，这条评论将从攻略和个人记录中移除。','删除评论？'))return;
  await api('/api/comments/'+button.dataset.deleteComment,{method:'DELETE'});toast('评论已删除');await render();return;
 }
 if(button.dataset.edit){openEditor(button.dataset.edit,Number(button.dataset.id));return;}
 if(button.dataset.publish){
  const row=state.adminRows.find(x=>Number(x.id)===Number(button.dataset.id));if(!row)return;
  const next=!Number(row.published);
  if(!await confirmAction(next?'内容将进入前台展示和搜索。':'内容将退出公开列表，已有收藏和评论记录保留。',next?'发布内容？':'下架内容？'))return;
  await api('/api/admin/'+button.dataset.publish+'/'+row.id+'/publication',{method:'PUT',body:{published:next,revision:row.revision}});toast(next?'内容已发布':'内容已下架');await render();return;
 }
 if(button.dataset.userStatus){
  const banned=button.dataset.status==='BANNED';
  if(!await confirmAction(banned?'该账号将无法登录，已有登录状态会失效。历史评论和收藏继续保留。':'该账号将恢复正常使用。',banned?'封禁账号？':'解封账号？'))return;
  const out=await api('/api/admin/users/'+button.dataset.userStatus+'/status',{method:'POST',body:{status:button.dataset.status}});toast(out.message);await render();return;
 }
 if(button.dataset.resetPassword){
  if(!await confirmAction('旧密码和已有登录状态将失效。用户下次登录需要修改临时密码。','重置这个用户的密码？'))return;
  const out=await api('/api/admin/users/'+button.dataset.resetPassword+'/reset-password',{method:'POST'});
  $('#editBody').innerHTML='<h2>密码已重置</h2><p class="muted">请将临时密码交给对应用户。这段密码只在此处显示一次。</p><div class="password-result">'+esc(out.temporary_password)+'</div><p class="small-text muted">用户登录后需要设置自己的新密码。</p><button class="button" data-close="editDialog">已记录，关闭</button>';$('#editDialog').showModal();return;
 }
 if(button.hasAttribute('data-add-section')){const count=document.querySelectorAll('[data-section]').length;if(count>=15){toast('最多填写 15 个小节',true);return;}$('#sectionEditors').insertAdjacentHTML('beforeend',editorSection());return;}
 if(button.hasAttribute('data-remove-section')){button.closest('[data-section]').remove();}
}
document.addEventListener('click',e=>{
 const button=e.target.closest('button');if(button){doAction(button).catch(error=>toast(error.message,true));return;}
 const link=e.target.closest('a');if(!link||link.target||e.metaKey||e.ctrlKey||e.shiftKey||e.altKey)return;
 const href=link.getAttribute('href');if(href && href.startsWith('/') && !href.startsWith('//') && !href.endsWith('.html') && !href.startsWith('/assets/') && !href.startsWith('/uploads/')){e.preventDefault();document.querySelectorAll('details[open]').forEach(x=>x.removeAttribute('open'));navigate(href);}
});
document.addEventListener('input',e=>{
 if(typeof contributionInputChanged==='function'&&contributionInputChanged(e.target))return;
 if(e.target.hasAttribute('data-city-filter'))void cityManagementChange(e.target);
 constraintPlannerChange(e.target);
 if(e.target.id==='commentBody'){const form=e.target.closest('[data-comment-form]');if(form)state.drafts.set(form.dataset.commentForm,e.target.value);}
 if(e.target.id==='adminFilter'){const q=e.target.value.trim().toLowerCase();document.querySelectorAll('[data-admin-row]').forEach(row=>row.hidden=!row.dataset.keyword.toLowerCase().includes(q));}
});
document.addEventListener('change',async e=>{
 if(contributionFilterChanged(e.target))return;
 await cityManagementChange(e.target);
 constraintPlannerChange(e.target);
 await depthChange(e.target);mealChange(e.target);compareChange(e.target);socialChange(e.target);await foodChange(e.target);
 featureChange(e.target);
 if(e.target.hasAttribute('data-task-key')){const input=e.target;input.disabled=true;try{const next=structuredClone(featureState.trip);next.checklist.find(i=>i.key===input.dataset.taskKey).done=input.checked;await saveTrip(next);await render();toast('清单已保存');}catch(error){input.checked=!input.checked;toast(error.message,true);}finally{input.disabled=false;}return;}
 const form=e.target.closest('#contentForm');if(!form)return;
 if(e.target.name==='city_id'){filterEditorSpots();return;}
 if(e.target.hasAttribute('data-image-preset') && e.target.value){form.elements.image.value=e.target.value;form.querySelector('.image-preview').src=e.target.value;}
 if(e.target.hasAttribute('data-upload') && e.target.files[0]){
  const file=e.target.files[0];if(file.size>5*1024*1024){toast('请选择 5 MB 以内的图片',true);return;}
  e.target.disabled=true;beginContentUpload(form);try{const data=new FormData();data.append('file',file);const out=await api('/api/admin/uploads',{method:'POST',body:data});if(form===$('#contentForm')&&$('#editDialog').open){form.elements.image.value=out.url;form.querySelector('.image-preview').src=out.url;toast('图片已上传');}}catch(error){toast(error.message,true);}finally{e.target.disabled=false;endContentUpload(form);}
 }
});
document.addEventListener('submit',async e=>{
 const form=e.target;if(!(form instanceof HTMLFormElement))return;e.preventDefault();
 if(await submitContributionForm(form,e))return;
 if(await contributionAdminSubmit(form,e))return;
 if(form.matches('.audit-filters')){navigate('/admin?'+new URLSearchParams(new FormData(form)));return;}
 if(form.hasAttribute('data-admin-search')){const p=new URLSearchParams(new FormData(form));p.set('page','1');navigate('/admin?'+p);return;}
 if(await submitCityManagementForm(form,e))return;
 if(await submitConstraintPlannerForm(form,e))return;
 if(await submitTripVersionsForm(form,e))return;
 if(await submitPlannerDepthForm(form,e))return;
 if(await submitMealForm(form,e))return;
 if(await submitDepthForm(form,e))return;
 if(await submitExperienceForm(form,e))return;
 if(await submitCompareForm(form,e))return;
 if(await submitBudgetForm(form,e))return;
 if(await submitSocialForm(form,e))return;
 if(await submitFoodForm(form,e))return;
 if(await submitFeedbackForm(form,e))return;
 if(await submitFeatureForm(form,e))return;
 if(form.hasAttribute('data-search')){e.preventDefault();const data=new FormData(form),q=String(data.get('q')||'').trim(),city=data.get('city')||'0',type=data.get('type')||'spot';navigate((form.dataset.target||'/search')+'?q='+encodeURIComponent(q)+'&city='+city+'&type='+type+'&theme='+encodeURIComponent(data.get('theme')||'')+'&duration='+encodeURIComponent(data.get('duration')||'')+'&sort='+encodeURIComponent(data.get('sort')||'relevance'));return;}
 if(!['authForm','contentForm'].includes(form.id) && !form.hasAttribute('data-comment-form') && !form.hasAttribute('data-password-form'))return;
 e.preventDefault();if(form.id==='authForm' && (loginCooldown.busy || $('#authSubmit').disabled))return;const submittedAuthMode=state.authMode,submittedUsername=form.id==='authForm'?form.elements.username.value:'';if(form.id==='authForm')loginCooldown.busy=true;if(form.id==='contentForm'&&Number(form.dataset.uploads||0)>0){toast('图片还在上传，请稍后保存',true);return;}const button=form.querySelector('[type=submit]'),errorBox=form.querySelector('.form-error');button.disabled=true;if(errorBox)errorBox.textContent='';
 try{
  const data=Object.fromEntries(new FormData(form));
  if(form.id==='authForm'){
   if(state.authMode==='register' && data.password!==data.confirm_password)throw new Error('两次输入的密码不一致');
   const info=await api('/api/auth/'+submittedAuthMode,{method:'POST',body:data});acceptSession(info);await loadFavorites();updateAccount();$('#authDialog').close();form.reset();toast(submittedAuthMode==='register'?'注册成功，已为你登录':'登录成功');await render();
   if(state.user.must_change_password){featureState.pending=null;navigate('/me?tab=password');toast('请先修改临时密码');}else await resumeFeatureIntent();
  }else if(form.hasAttribute('data-comment-form')){
   const id=form.dataset.commentForm;await api('/api/guides/'+id+'/comments',{method:'POST',body:{body:data.body}});state.drafts.delete(id);toast('评论已发表');await render();document.querySelector('.comments-section')?.scrollIntoView({block:'start'});
  }else if(form.hasAttribute('data-password-form')){
   if(data.new_password!==data.confirm_password)throw new Error('两次输入的新密码不一致');
   const info=await api('/api/user/password',{method:'POST',body:data});acceptSession(info);updateAccount();toast('密码已修改');await render();await resumeContributionIntent();
  }else{
   data.published=data.published==='1';
   data.duration_minutes=Number(data.duration_minutes);if(form.dataset.kind==='spots')data.gallery=JSON.parse(data.gallery||'[]');
   if(form.dataset.kind==='guides'){data.sections=Array.from(form.querySelectorAll('[data-section]')).map(section=>({title:section.querySelector('[data-section-title]').value,body:section.querySelector('[data-section-body]').value}));data.spot_ids=Array.from(form.querySelectorAll('[name=spot_ids]:checked')).map(x=>Number(x.value));}
   const id=Number(form.dataset.id);if(id)data.revision=Number(form.dataset.revision);const url='/api/admin/'+form.dataset.kind+(id?'/'+id:'');await api(url,{method:id?'PUT':'POST',body:data});$('#editDialog').close();toast('内容已保存');await render();
  }
 }catch(error){if(form.id==='authForm' && submittedAuthMode==='login' && error.code==='LOGIN_COOLDOWN')rememberLoginCooldown(error,submittedUsername);if(errorBox){if(form.id!=='authForm'||authErrorIsCurrent(submittedAuthMode,submittedUsername))errorBox.textContent=error.message;}else toast(error.message,true);if(error.status===401 && form.id!=='authForm')openAuth();}
 finally{if(form.id==='authForm'){loginCooldown.busy=false;paintLoginCooldown();}else button.disabled=false;}
});
window.addEventListener('popstate',async event=>{
 if(restoringRoute){restoringRoute=false;return;}
 const next=Number(event.state?.roamIndex||0),previous=routeIndex;
 if(formSafety.hasDirtyDialogs()&&!await formSafety.allowLeave()){
  const offset=previous-Number(history.state?.roamIndex||0);
  if(offset){restoringRoute=true;history.go(offset);}else history.replaceState({roamIndex:previous},'',routeUrl);
  return;
 }
 rememberRouteScroll();routeIndex=next;routeUrl=location.href;await render({scroll:routeScroll.get(next)||0});
});
document.querySelectorAll('[data-icon]').forEach(el=>el.innerHTML=icon(el.dataset.icon));
(async()=>{try{await Promise.all([refreshSession(),api('/api/cities').then(x=>state.cities=x)]);await loadFavorites();await render();if(state.user?.must_change_password && location.pathname!=='/me')navigate('/me?tab=password');}catch(error){$('#main').innerHTML='<div class="error-page"><h1>暂时无法连接网站</h1><p>'+esc(error.message)+'</p><button class="button" data-action="retry">重新加载</button></div>';}})();

document.querySelector('#authForm [name=username]').addEventListener('input',()=>{$('#authError').textContent='';paintLoginCooldown();});
