'use strict';
// City previews use public search results; no account or trip data is changed here.
const destinationExplorerUi={serial:0,context:null};
function destinationExplorerLive(c){return destinationExplorerUi.context===c&&state.renderId===c.renderId&&location.pathname==='/destinations'&&document.getElementById('destinationExplorer')?.dataset.context===String(c.token);}
function destinationExplorerRows(c,theme){
 if(c.cache.has(theme))return c.cache.get(theme);
 const request=Promise.all(c.cities.map(async city=>{const result=await api('/api/search?type=spot&size=3&sort=editor&city='+Number(city.id)+'&theme='+encodeURIComponent(theme));return {city,spots:result.items,total:Number(result.total)};}));
 c.cache.set(theme,request);
 request.catch(()=>{if(c.cache.get(theme)===request)c.cache.delete(theme);});
 return request;
}
function destinationExplorerVisible(c){return c.rows.filter(row=>!c.theme||row.total>0);}
function destinationExplorerIndex(c){return c.rows.map(({city,total})=>'<button type="button" class="destination-index-item" data-destination="city" data-value="'+esc(city.slug)+'" aria-pressed="'+(city.slug===c.selected)+'" aria-controls="destinationCityFeature" '+(c.theme&&total===0?'disabled':'')+'>'+(city.image?'<img src="'+esc(city.image)+'" alt="" loading="lazy">':'<span class="destination-index-placeholder">'+icon('pin',22)+'</span>')+'<span><small>'+esc(city.province)+'</small><strong>'+esc(city.name)+'</strong><span>'+esc(c.theme?(total?total+' 处'+c.theme+'景点':'该主题暂无景点'):city.tagline)+'</span></span>'+icon('arrow',17)+'</button>').join('')||'<p class="destination-index-empty">目的地正在整理中。</p>';}
function destinationExplorerFeature(c){
 const row=destinationExplorerVisible(c).find(row=>row.city.slug===c.selected);if(!row)return '<div class="destination-no-match"><h2>换个主题，看看别处的风景</h2><p>当前还没有符合这个主题的目的地。</p><button class="button" data-destination="theme" data-value="">查看全部目的地</button></div>';
 const city=row.city;
 return (city.image?'<img class="destination-feature-image" src="'+esc(city.image)+'" alt="'+esc(city.name)+'风景">':'<div class="destination-feature-fallback">'+icon('pin',64)+'</div>')+'<div class="destination-feature-facts"><span>'+esc(city.province)+'</span><span>'+Number(city.spot_count||0)+' 处景点 / '+Number(city.guide_count||0)+' 篇攻略</span></div><div class="destination-feature-copy"><h2 id="destinationFeatureTitle">'+esc(city.name)+'</h2><p class="destination-feature-tagline">'+esc(city.tagline)+'</p><p class="destination-feature-description">'+esc(city.description)+'</p><div class="button-row"><a class="button" href="/destination/'+esc(city.slug)+'">走进'+esc(city.name)+' '+icon('arrow',17)+'</a><a class="destination-guide-link" href="/guides?city='+Number(city.id)+'">读这里的攻略</a><button class="button secondary small" data-contribution-start="spot" data-city-id="'+esc(city.id)+'">补充景点</button></div></div>';
}
function destinationExplorerSpots(c){
 const row=destinationExplorerVisible(c).find(row=>row.city.slug===c.selected);if(!row)return '';
 const city=row.city,theme=c.theme,href='/search?city='+Number(city.id)+'&theme='+encodeURIComponent(theme);
 return '<div class="section-heading"><div><h2>'+(theme?esc(city.name)+'的'+esc(theme):'先认识'+esc(city.name)+'的这些地方')+'</h2><p>'+(theme?'找到 '+row.total+' 处相关景点，挑几处走近看看。':'从景点开始，再决定这座城市适不适合这次出发。')+'</p></div><div class="destination-contribution-actions"><a class="text-link" href="'+esc(href)+'">查看全部 '+row.total+' 处 '+icon('arrow',16)+'</a><button class="text-button" data-contribution-start="spot" data-city-id="'+esc(city.id)+'">补充景点</button></div></div>'+(row.spots.length?grid(row.spots,'spot'):'<div class="destination-preview-empty"><p>这里的景点正在整理，可以先看看城市介绍。</p><a class="text-link" href="/destination/'+esc(city.slug)+'">了解'+esc(city.name)+'</a></div>');
}
function destinationExplorerNotes(c){
 const row=destinationExplorerVisible(c).find(row=>row.city.slug===c.selected);if(!row)return '';
 const city=row.city,notes=[['怎么逛',city.visit_advice],['怎么去',city.travel_advice],['吃什么',city.food]].filter(([,body])=>body);
 return '<div class="destination-notes-heading"><h2>出发前，想得再具体一点</h2><a class="text-link" href="/recommend?city='+Number(city.id)+'&theme='+encodeURIComponent(c.theme)+'">安排'+esc(city.name)+'行程 '+icon('arrow',17)+'</a></div>'+(notes.length?'<div class="destination-notes-grid">'+notes.map(([title,body])=>'<article><h3>'+title+'</h3><p>'+esc(body)+'</p></article>').join('')+'</div>':'<p>可以从景点和攻略开始，按自己的节奏组合行程。</p>');
}
function destinationExplorerRemember(c){const url=new URL(location.href);if(c.selected)url.searchParams.set('city',c.selected);else url.searchParams.delete('city');if(c.theme)url.searchParams.set('theme',c.theme);else url.searchParams.delete('theme');history.replaceState(history.state,'',url.pathname+url.search+url.hash);}
function paintDestinationExplorer(c,focusCity=false){
 if(!destinationExplorerLive(c))return;
 const visible=destinationExplorerVisible(c);if(!visible.some(row=>row.city.slug===c.selected))c.selected=visible[0]?.city.slug||'';
 const index=document.getElementById('destinationCityIndex'),indexScroll=index.scrollTop||0;
 index.innerHTML=destinationExplorerIndex(c);index.scrollTop=indexScroll;
 document.getElementById('destinationCityFeature').innerHTML=destinationExplorerFeature(c);
 document.getElementById('destinationSpotPreview').innerHTML=destinationExplorerSpots(c);
 document.getElementById('destinationTravelNotes').innerHTML=destinationExplorerNotes(c);
 document.querySelectorAll('[data-destination="theme"]').forEach(button=>button.setAttribute('aria-pressed',String(button.dataset.value===c.theme)));
 document.getElementById('destinationMatchCount').textContent=visible.length+' 个目的地'+(c.theme?'符合这个主题':'可以探索');
 const city=visible.find(row=>row.city.slug===c.selected)?.city;
 document.getElementById('destinationExplorerStatus').textContent=city?'正在预览'+city.name+'，点击“走进'+city.name+'”查看完整介绍。':'可以切换主题，继续挑选目的地。';
 document.getElementById('destinationExplorer').removeAttribute('aria-busy');
 if(focusCity)[...document.querySelectorAll('[data-destination="city"]')].find(button=>button.dataset.value===c.selected)?.focus({preventScroll:true});
 destinationExplorerRemember(c);
}
async function destinationExplorerPage(){
 const params=new URLSearchParams(location.search),theme=travelThemes.includes(params.get('theme'))?params.get('theme'):'';
 const c={token:++destinationExplorerUi.serial,renderId:state.renderId,cities:[...state.cities],theme,selected:params.get('city')||state.cities[0]?.slug||'',cache:new Map(),rows:[],request:0};
 destinationExplorerUi.context=c;c.rows=await destinationExplorerRows(c,theme);
 const visible=destinationExplorerVisible(c);if(!visible.some(row=>row.city.slug===c.selected))c.selected=visible[0]?.city.slug||'';
 return '<div class="destination-discovery-canvas"><div id="destinationExplorer" class="container destination-explorer" data-context="'+c.token+'"><header class="destination-discovery-heading"><div><h1>下一站，想走进哪座城？</h1><p>沿着山水、街巷与人文，找到这次想慢慢认识的地方。</p></div><a class="text-link" href="/routes">看看大家怎么走 '+icon('arrow',17)+'</a></header><div class="destination-theme-bar"><div class="destination-theme-options" role="group" aria-label="按旅行兴趣筛选目的地">'+['',...travelThemes].map(value=>'<button type="button" data-destination="theme" data-value="'+esc(value)+'" aria-pressed="'+(value===theme)+'">'+esc(value||'全部目的地')+'</button>').join('')+'</div><p id="destinationMatchCount">'+visible.length+' 个目的地'+(theme?'符合这个主题':'可以探索')+'</p></div><div id="destinationExplorerStatus" class="destination-explorer-status" role="status">点击城市切换预览，找到合拍的一站。</div><div class="destination-workbench"><nav class="destination-city-index" aria-label="选择要预览的城市"><h2>城市索引</h2><div id="destinationCityIndex">'+destinationExplorerIndex(c)+'</div></nav><section id="destinationCityFeature" class="destination-city-feature" aria-label="城市预览">'+destinationExplorerFeature(c)+'</section></div><section id="destinationSpotPreview" class="destination-spot-preview" aria-label="当前城市的景点">'+destinationExplorerSpots(c)+'</section><section id="destinationTravelNotes" class="destination-travel-notes" aria-label="当前城市的旅行提示">'+destinationExplorerNotes(c)+'</section></div></div>';
}
async function destinationExplorerAction(button){
 const action=button.dataset.destination;if(!action)return false;
 const c=destinationExplorerUi.context;if(!c||!destinationExplorerLive(c))return true;
 if(action==='city'){
  if(!destinationExplorerVisible(c).some(row=>row.city.slug===button.dataset.value))return true;
  c.request++;c.selected=button.dataset.value;paintDestinationExplorer(c,true);return true;
 }
 if(action==='theme'){
  const theme=button.dataset.value;if(theme&&!travelThemes.includes(theme))return true;
  const request=++c.request;document.getElementById('destinationExplorer').setAttribute('aria-busy','true');document.getElementById('destinationExplorerStatus').textContent='正在寻找'+(theme||'全部')+'目的地…';
  try{const rows=await destinationExplorerRows(c,theme);if(!destinationExplorerLive(c)||request!==c.request)return true;c.rows=rows;c.theme=theme;paintDestinationExplorer(c);}
  catch(error){if(destinationExplorerLive(c)&&request===c.request){document.getElementById('destinationExplorer').removeAttribute('aria-busy');document.getElementById('destinationExplorerStatus').textContent='这次筛选没有加载完成，仍保留当前预览。请再点一次主题重试。';}}
  return true;
 }
 return true;
}
