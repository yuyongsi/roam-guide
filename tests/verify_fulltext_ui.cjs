const fs=require('fs'),vm=require('vm'),assert=require('assert');
const context=vm.createContext({});
vm.runInContext(`const esc=v=>String(v==null?'':v).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));`+fs.readFileSync(require('path').join(__dirname,'../src/main/resources/static/js/fulltext-search.js'),'utf8'),context);
const out=vm.runInContext(`searchSegments([{text:'<img src=x onerror=alert(1)>',match:false},{text:'汉画<script>',match:true}])`,context);
assert(!out.includes('<img'));assert(!out.includes('<script>'));assert(out.includes('<mark>汉画&lt;script&gt;</mark>'));
assert.equal(vm.runInContext(`searchSegments(null,'<unsafe>')`,context),'&lt;unsafe&gt;');
assert.equal(vm.runInContext(`searchEvidence({})`,context),'');
assert(vm.runInContext(`searchEvidence({search:{fields:['body','location']}})`,context).includes('正文、地区'));
console.log('PASS 6 actual-source UI safety assertions');
