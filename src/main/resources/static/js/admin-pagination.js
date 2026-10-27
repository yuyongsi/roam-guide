// 管理列表搜索与分页均由服务端处理。
async function adminListPage(section){
 const version=state.renderId,user=state.user?.id,p=new URLSearchParams(location.search),q=(p.get('q')||'').slice(0,100),size=[10,20,50].includes(Number(p.get('size')))?Number(p.get('size')):20,page=Math.max(1,Number.parseInt(p.get('page'),10)||1);
 const data=await api('/api/admin/lists/'+section+'?q='+encodeURIComponent(q)+'&size='+size+'&page='+page);
 if(version===state.renderId&&user===state.user?.id&&data.page!==page){p.set('page',data.page);history.replaceState(history.state,'','/admin?'+p.toString());routeUrl=location.href;}
 return {...data,q};
}
function adminSearch(d,section,extra=''){
 const p=new URLSearchParams(location.search),city=p.get('city_id');
 return '<form class="admin-toolbar" data-admin-search action="/admin" method="get"><input type="hidden" name="section" value="'+esc(section)+'">'+(city?'<input type="hidden" name="city_id" value="'+esc(city)+'">':'')+'<input type="search" name="q" value="'+esc(d.q)+'" maxlength="100" placeholder="搜索全部记录" aria-label="搜索全部记录"><label>每页 <select name="size" aria-label="每页条数">'+[10,20,50].map(n=>'<option value="'+n+'" '+(d.size===n?'selected':'')+'>'+n+'</option>').join('')+'</select> 条</label><button class="button secondary small" type="submit">搜索</button><a class="text-link" href="'+adminPageUrl(1,{q:''})+'">清空</a>'+extra+'</form>';
}
function adminPageUrl(page,overrides={}){const p=new URLSearchParams(location.search);p.set('page',page);for(const [k,v] of Object.entries(overrides))v?p.set(k,v):p.delete(k);return '/admin?'+p.toString();}
function adminPagination(d){const pages=Math.max(1,Math.ceil(d.total/d.size));return '<nav class="admin-pagination" aria-label="管理列表分页"><span>共 '+d.total+' 条 · 第 '+d.page+' / '+pages+' 页</span><div class="button-row">'+[[1,'首页',d.page===1],[d.page-1,'上一页',d.page===1],[d.page+1,'下一页',d.page===pages],[pages,'末页',d.page===pages]].map(([p,label,disabled])=>disabled?'<span class="muted">'+label+'</span>':'<a class="text-link" href="'+adminPageUrl(p)+'">'+label+'</a>').join('')+'</div></nav>'; }
