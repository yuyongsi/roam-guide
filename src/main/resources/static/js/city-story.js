'use strict';

function cityStoryHandleAnchorClick(event){
 const link=event.target.closest?.('.city-story-nav a');if(!link)return;
 const page=document.querySelector('.city-story');if(!page)return;
 const hash=link.getAttribute('href');if(!/^#[a-z][a-z0-9_-]*$/i.test(hash||'')){event.preventDefault();return;}
 const target=document.getElementById(hash.slice(1));if(!target||!page.contains(target)){event.preventDefault();return;}
 event.preventDefault();history.replaceState(history.state,'',location.pathname+location.search+hash);
 const reduced=window.matchMedia?.('(prefers-reduced-motion: reduce)').matches||false;
 target.scrollIntoView({block:'start',behavior:reduced?'instant':'smooth'});
}
if(typeof document!=='undefined')document.addEventListener('click',cityStoryHandleAnchorClick);

async function cityStoryPage(slug){
 const city=state.cities.find(x=>x.slug===slug);if(!city)throw new Error('这个目的地还没有收录');
 const [spots,guides,foods]=await Promise.all([
  api('/api/search?type=spot&size=24&city='+city.id),
  api('/api/search?type=guide&size=24&city='+city.id),
  api('/api/foods?size=6&city='+city.id)
 ]);
 const spotTotal=Number(spots.total)||0,guideTotal=Number(guides.total)||0,foodTotal=Number(foods.total)||0;
 const spotHref='/search?city='+encodeURIComponent(city.id)+'&type=spot';
 const guideHref='/guides?city='+encodeURIComponent(city.id);
 const foodHref='/food?city='+encodeURIComponent(city.id);
 const photos=[],seen=new Set([String(city.image||'')]);
 for(const spot of spots.items){const image=String(spot.image||'');if(!image||seen.has(image))continue;seen.add(image);photos.push(spot);if(photos.length===2)break;}
 const hasCover=Boolean(String(city.image||'').trim()),collageVariant=' city-story-collage--photos-'+photos.length+(hasCover?' city-story-collage--cover':' city-story-collage--no-cover');
 const advice=[['游览建议',city.visit_advice],['出行建议',city.travel_advice]].filter(([,text])=>String(text||'').trim());
 const adviceSection=advice.length?'<section class="city-story-advice" id="advice" aria-labelledby="cityAdviceTitle"><div class="city-story-section-head"><div><h2 id="cityAdviceTitle">游览建议</h2><p>先了解游览节奏与交通衔接，再按自己的时间安排。</p></div></div><div class="city-story-advice-columns">'+advice.map(([title,text])=>'<article><h3>'+esc(title)+'</h3><p>'+esc(text)+'</p></article>').join('')+'</div></section>':'<section class="city-story-advice" id="advice" aria-labelledby="cityAdviceTitle"><div class="city-story-section-head"><div><h2 id="cityAdviceTitle">游览建议</h2></div></div><p class="city-story-empty">这座城市的行程建议正在整理中。</p></section>';
 const heroPhotos=photos.map((spot,index)=>'<figure class="city-story-photo city-story-photo-'+(index+1)+'"><a href="/spot/'+encodeURIComponent(spot.id)+'"><img src="'+esc(spot.image)+'" alt="'+esc(spot.title)+'" '+(index?'loading="lazy"':'fetchpriority="high"')+'><span class="city-story-photo-caption">'+esc(spot.title)+'</span></a></figure>').join('');
 const photoNotes=photos.map(photoNote).filter(Boolean).join('');
 const hero='<section class="city-story-hero" aria-label="'+esc(city.name)+'城市介绍"><div class="city-story-intro"><p class="city-story-province">'+esc(city.province)+'</p><h1>'+esc(city.name)+'</h1>'+(city.tagline?'<p class="city-story-tagline">'+esc(city.tagline)+'</p>':'')+'<p class="city-story-description">'+esc(city.description)+'</p><div class="city-story-counts" aria-label="城市资料数量"><span><strong>'+spotTotal+'</strong> 个景点</span><span><strong>'+guideTotal+'</strong> 篇攻略</span></div><div class="city-story-contribution-actions"><a class="city-story-plan" href="/recommend?city='+encodeURIComponent(city.id)+'">安排城市行程 '+icon('arrow',17)+'</a><button class="button secondary small" data-contribution-start="spot" data-city-id="'+esc(city.id)+'">补充景点</button></div></div><div class="city-story-media"><div class="city-story-collage'+collageVariant+'"><figure class="city-story-cover">'+(hasCover?'<img src="'+esc(city.image)+'" alt="'+esc(city.name)+'城市封面" fetchpriority="high">':'<div class="city-story-cover-placeholder" role="img" aria-label="'+esc(city.name)+'：暂无城市封面">'+esc(city.name)+'</div>')+'<figcaption>'+esc(city.name)+'</figcaption></figure>'+heroPhotos+'</div>'+photoNotes+'</div></section>';
 const nav='<nav class="city-story-nav" aria-label="本页内容"><a href="#advice">游览建议</a><a href="#spots">景点 <span>'+spotTotal+'</span></a><a href="#guides">攻略 <span>'+guideTotal+'</span></a><a href="#foods">美食 <span>'+foodTotal+'</span></a></nav>';
 const spotItems=spots.items.slice(0,6),guideItems=guides.items.slice(0,3);
 const spotsSection='<section class="city-story-section city-story-spots" id="spots" aria-labelledby="citySpotsTitle"><div class="city-story-section-head"><div><h2 id="citySpotsTitle">认识这里的景点</h2><p>从真实收录的地点开始，慢慢拼出城市的样子。</p></div><div class="city-story-section-actions"><a href="'+spotHref+'">全部 '+spotTotal+' 个景点 '+icon('arrow',17)+'</a><button class="text-button" data-contribution-start="spot" data-city-id="'+esc(city.id)+'">补充景点</button></div></div>'+(spotItems.length?'<div class="city-story-spot-grid">'+grid(spotItems,'spot')+'</div>':empty('景点资料正在整理','目前还没有公开的景点内容。','/destinations','返回目的地'))+'</section>';
 const guidesSection='<section class="city-story-section city-story-guides" id="guides" aria-labelledby="cityGuidesTitle"><div class="city-story-section-head"><div><h2 id="cityGuidesTitle">关于这里的攻略</h2><p>交通、节奏与游览细节，出发前可以再读一遍。</p></div><div class="city-story-section-actions"><a href="'+guideHref+'">全部 '+guideTotal+' 篇攻略 '+icon('arrow',17)+'</a><button class="text-button" data-contribution-start="guide" data-city-id="'+esc(city.id)+'">分享攻略</button></div></div>'+(guideItems.length?'<div class="city-story-guide-grid">'+grid(guideItems,'guide')+'</div>':empty('攻略资料正在整理','目前还没有公开的城市攻略。'))+'</section>';
 const foodDescription=city.food?'<div class="city-story-food-note"><p>'+esc(city.food)+'</p>'+(city.food_source?'<a href="'+esc(city.food_source)+'" target="_blank" rel="noopener">查看食味资料来源</a>':'')+'</div>':'';
 const foodContent=foods.items.length?foodGrid(foods.items):empty('美食资料正在整理','目前还没有公开的地方美食。');
 const foodColumns=Math.max(1,Math.min(3,foods.items.length));
 const foodSection='<section class="city-story-section city-story-food" id="foods" aria-labelledby="cityFoodsTitle"><div class="city-story-section-head"><div><h2 id="cityFoodsTitle">这座城的美食</h2><p>顺着一份地方食味，认识街巷里的日常。</p></div><div class="city-story-section-actions"><a href="'+foodHref+'">全部 '+foodTotal+' 道美食 '+icon('arrow',17)+'</a><button class="text-button" data-contribution-start="food" data-city-id="'+esc(city.id)+'">推荐地方美食</button></div></div>'+foodDescription+'<div class="city-story-food-grid city-story-food-grid--items-'+foodColumns+'">'+foodContent+'</div></section>';
 const footer='<footer class="city-story-footer"><p>把想去的地方放进行程，留出适合自己的节奏。</p><div><a href="/planner?city='+encodeURIComponent(city.id)+'">用约束规划安排</a><a href="/destinations?city='+encodeURIComponent(city.slug)+'">返回目的地</a></div></footer>';
 return '<div class="city-story-canvas"><div class="container city-story">'+crumb([{label:'目的地',url:'/destinations?city='+encodeURIComponent(city.slug)},{label:city.name}])+hero+nav+adviceSection+spotsSection+guidesSection+foodSection+footer+'</div></div>';
}
