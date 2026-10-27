'use strict';
// 所有片段先转义，再加入固定的 mark 标签；不信任正文中的 HTML。
function searchSegments(parts,fallback=''){
 if(!Array.isArray(parts))return esc(fallback);
 return parts.map(p=>p.match?'<mark>'+esc(p.text)+'</mark>':esc(p.text)).join('');
}
function searchEvidence(item){
 if(!item.search)return '';
 const names={title:'标题',intro:'简介',body:'正文',location:'地区'};
 return '<div class="search-evidence">命中'+item.search.fields.map(f=>names[f]).filter(Boolean).join('、')+'</div>';
}
